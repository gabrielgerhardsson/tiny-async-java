package eu.toolchain.async;

import eu.toolchain.async.RecursionSafeAsyncCaller;
import java.util.Collection;
import java.util.Iterator;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Coordinator thread for handling delayed callables executing with a given parallelism.
 *
 * @param <S> The source type being collected.
 * @param <T> The target type the source type is being collected into.
 */
public class DelayedCollectCoordinator<S, T> implements FutureDone<S>, Runnable {
    private final AtomicInteger pending = new AtomicInteger();

    private final AtomicInteger cancelled = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();

    /* lock that must be acquired before using {@link callables} */
    private final Object lock = new Object();

    private RecursionSafeAsyncCaller recursionSafeAsyncCaller;
    private final AsyncCaller caller;
    private final Iterator<? extends Callable<? extends AsyncFuture<? extends S>>> callables;
    private final StreamCollector<? super S, ? extends T> collector;
    private final ResolvableFuture<? super T> future;
    private final int parallelism;
    private final int total;

    volatile boolean cancel = false;
    volatile boolean done = false;

    public DelayedCollectCoordinator(
        final ExecutorService executorService,
        final AsyncCaller caller,
        final Collection<? extends Callable<? extends AsyncFuture<? extends S>>> callables,
        final StreamCollector<S, T> collector,
        final ResolvableFuture<? super T> future,
        int parallelism,
        int maxRecursionDepth
    ) {
        if (executorService != null) {
            recursionSafeAsyncCaller =
                new RecursionSafeAsyncCaller(executorService, caller, maxRecursionDepth);
        }
        this.caller = caller;
        this.callables = callables.iterator();
        this.collector = collector;
        this.future = future;
        this.parallelism = parallelism;
        this.total = callables.size();
    }

    public DelayedCollectCoordinator(
        final ExecutorService executorService,
        final AsyncCaller caller,
        final Collection<? extends Callable<? extends AsyncFuture<? extends S>>> callables,
        final StreamCollector<S, T> collector,
        final ResolvableFuture<? super T> future,
        int parallelism
    ) {
        /*
         * The default maxRecursionDepth is 100, based on a failure example where stack overflow
         * happened at 1700 recursions. This obviously highly depends on the size of each stack
         * frame, but 100 seems like a sane default.
         */
        this(executorService, caller, callables, collector, future, parallelism, 100);
    }

    public DelayedCollectCoordinator(
        final AsyncCaller caller,
        final Collection<? extends Callable<? extends AsyncFuture<? extends S>>> callables,
        final StreamCollector<S, T> collector, final ResolvableFuture<? super T> future,
        int parallelism
    ) {
        /*
         * Fallback for maintaining signature stability. This will disable deferred execution and
         * thus disable stack overflow protection.
         */
        this((ExecutorService)null, caller, callables, collector, future, parallelism);
    }

    @Override
    public void failed(Throwable cause) {
        caller.fail(collector, cause);
        pending.decrementAndGet();
        failed.incrementAndGet();
        cancel = true;
        checkNext();
    }

    @Override
    public void resolved(S result) {
        caller.resolve(collector, result);
        pending.decrementAndGet();
        // There's now a slot free in the executor, let's see if there's an Callable left
        // to setup and get going.
        checkNext();
    }

    @Override
    public void cancelled() {
        caller.cancel(collector);
        pending.decrementAndGet();
        cancelled.incrementAndGet();
        cancel = true;
        checkNext();
    }

    // coordinate thread.
    @Override
    public void run() {
        synchronized (lock) {
            if (!callables.hasNext()) {
                checkEnd();
                return;
            }

            for (int i = 0; i < parallelism && callables.hasNext(); i++) {
                setupNext(callables.next());
            }
        }

        future.onCancelled(new FutureCancelled() {
            @Override
            public void cancelled() throws Exception {
                cancel = true;
                checkNext();
            }
        });
    }

    private void checkNext() {
        final Callable<? extends AsyncFuture<? extends S>> next;

        synchronized (lock) {
            // cancel any available callbacks.
            if (cancel) {
                while (callables.hasNext()) {
                    // Step the iterator forward once, ignoring the returned value. This AsyncFuture
                    // hasn't been setup yet, so there's nothing to cancel.
                    callables.next();
                    // Indicate to the collector that an AsyncFuture was "cancelled"
                    caller.cancel(collector);
                    cancelled.incrementAndGet();
                    // The loop will eventually step through all the remaining callables, causing
                    // x calls to caller.cancel(), indicating the number of AsyncFuture's that
                    // were cancelled.
                }
            }

            if (!callables.hasNext()) {
                checkEnd();
                return;
            }

            next = callables.next();
        }

        setupNext(next);
    }

    private void setupNext(final Callable<? extends AsyncFuture<? extends S>> next) {

        pending.incrementAndGet();

        Runnable toRun = () -> {
            AsyncFuture<? extends S> f;

            try {
                f = next.call();
            } catch (final Exception e) {
                failed(e);
                return;
            }

            if (f != null) {
                f.onDone(this);
            }
        };

        if (recursionSafeAsyncCaller != null) {
            recursionSafeAsyncCaller.execute(toRun);
        } else {
            // Fallback
            toRun.run();
        }
    }

    private void checkEnd() {
        if (pending.get() > 0) {
            return;
        }

        if (done) {
            return;
        }

        done = true;

        final int f = failed.get();
        final int c = cancelled.get();
        final int r = total - f - c;

        final T value;

        try {
            value = collector.end(r, f, c);
        } catch (Exception e) {
            future.fail(e);
            return;
        }

        future.resolve(value);
    }
}
