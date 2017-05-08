package eu.toolchain.async.concurrent;

import eu.toolchain.async.AsyncFramework;
import eu.toolchain.async.AsyncFuture;
import eu.toolchain.async.Borrowed;
import eu.toolchain.async.FutureDone;
import eu.toolchain.async.FutureFinished;
import eu.toolchain.async.LazyTransform;
import eu.toolchain.async.Managed;
import eu.toolchain.async.ManagedAction;
import eu.toolchain.async.ManagedSetup;
import eu.toolchain.async.ResolvableFuture;
import eu.toolchain.async.TinyStackUtils;
import eu.toolchain.async.Transform;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class ConcurrentManaged<T> implements Managed<T> {
    private static final boolean TRACING;
    private static final boolean CAPTURE_STACK;

    // fetch and compare the value of properties that modifies runtime behaviour of this class.
    static {
        TRACING = "on".equals(System.getProperty(Managed.TRACING, "off"));
        CAPTURE_STACK = "on".equals(System.getProperty(Managed.CAPTURE_STACK, "off"));
    }

    static final InvalidBorrowed<?> INVALID = new InvalidBorrowed<>();

    static final StackTraceElement[] EMPTY_STACK = new StackTraceElement[0];

    private final AsyncFramework async;
    private final ManagedSetup<T> setup;

    // the managed reference.
    protected final AtomicReference<T> reference = new AtomicReference<>();

    // acts to allow only a single thread to setup the reference.
    private final ResolvableFuture<Void> startFuture;
    private final ResolvableFuture<Void> zeroLeaseFuture;
    private final ResolvableFuture<T> stopReferenceFuture;

    // composite future that depends on zero-lease, and stop-reference.
    private final AsyncFuture<Void> stopFuture;

    protected final Set<ValidBorrowed<T>> traces;

    protected final AtomicReference<ManagedState> state =
        new AtomicReference<ManagedState>(ManagedState.INITIALIZED);

    /**
     * The number of borrowed references that are out in the wild.
     */
    protected final AtomicInteger leases = new AtomicInteger(1);

    public static <T> ConcurrentManaged<T> newManaged(
        final AsyncFramework async, final ManagedSetup<T> setup
    ) {
        final ResolvableFuture<Void> startFuture = async.future();
        final ResolvableFuture<Void> zeroLeaseFuture = async.future();
        final ResolvableFuture<T> stopReferenceFuture = async.future();

        final AsyncFuture<Void> stopFuture =
            zeroLeaseFuture.lazyTransform(new LazyTransform<Void, Void>() {
                @Override
                public AsyncFuture<Void> transform(Void v) throws Exception {
                    return stopReferenceFuture.lazyTransform(new LazyTransform<T, Void>() {
                        @Override
                        public AsyncFuture<Void> transform(T reference) throws Exception {
                            return setup.destruct(reference);
                        }
                    });
                }
            });

        return new ConcurrentManaged<T>(async, setup, startFuture, zeroLeaseFuture,
            stopReferenceFuture, stopFuture);
    }

    protected ConcurrentManaged(
        final AsyncFramework async, final ManagedSetup<T> setup,
        final ResolvableFuture<Void> startFuture, final ResolvableFuture<Void> zeroLeaseFuture,
        final ResolvableFuture<T> stopReferenceFuture, final AsyncFuture<Void> stopFuture
    ) {
        this.async = async;
        this.setup = setup;

        this.startFuture = startFuture;
        this.zeroLeaseFuture = zeroLeaseFuture;
        this.stopReferenceFuture = stopReferenceFuture;
        this.stopFuture = stopFuture;

        if (TRACING) {
            traces = Collections.newSetFromMap(new ConcurrentHashMap<ValidBorrowed<T>, Boolean>());
        } else {
            traces = null;
        }
    }

    @Override
    public <R> AsyncFuture<R> doto(final ManagedAction<T, R> action) {
        // pre-emptively increase the number of leases in order to prevent the underlying object
        // (if valid) to be
        // allocated.

        final Borrowed<T> b = borrow();

        if (!b.isValid()) {
            throw new IllegalStateException("Managed reference is not valid");
        }

        final T reference = b.get();

        final AsyncFuture<R> f;

        try {
            f = action.action(reference);
        } catch (Exception e) {
            b.release();
            return async.failed(e);
        }

        return f.onFinished(b.releasing());
    }

    @Override
    public Borrowed<T> borrow() {
        // pre-emptively increase the number of leases in order to prevent the underlying object
        // (if valid) to be
        // allocated.
        retain();

        final T value = reference.get();

        if (value == null) {
            release();
            return invalid();
        }

        final ValidBorrowed<T> b = new ValidBorrowed<T>(this, async, value, getStackTrace());

        if (TRACING) {
            traces.add(b);
        }

        return b;
    }

    @Override
    public boolean isReady() {
        return startFuture.isDone();
    }

    @Override
    public AsyncFuture<Void> start() {
        if (!state.compareAndSet(ManagedState.INITIALIZED, ManagedState.STARTED)) {
            return startFuture;
        }

        final AsyncFuture<T> constructor;

        try {
            constructor = setup.construct();
        } catch (Exception e) {
            return async.failed(e);
        }

        return constructor.directTransform(new Transform<T, Void>() {
            @Override
            public Void transform(T result) throws Exception {
                if (result == null) {
                    throw new IllegalArgumentException("setup reference must no non-null");
                }

                reference.set(result);
                return null;
            }
        }).onDone(new FutureDone<Void>() {
            @Override
            public void failed(Throwable cause) throws Exception {
                startFuture.fail(cause);
            }

            @Override
            public void resolved(Void result) throws Exception {
                startFuture.resolve(null);
            }

            @Override
            public void cancelled() throws Exception {
                startFuture.cancel();
            }
        });
    }

    @Override
    public AsyncFuture<Void> stop() {
        if (!state.compareAndSet(ManagedState.STARTED, ManagedState.STOPPED)) {
            return stopFuture;
        }

        stopReferenceFuture.resolve(this.reference.getAndSet(null));

        // release self-reference.
        release();
        return stopFuture;
    }

    protected void retain() {
        leases.incrementAndGet();
    }

    protected void release() {
        final int lease = leases.decrementAndGet();

        if (lease == 0) {
            zeroLeaseFuture.resolve(null);
        }
    }

    @Override
    public String toString() {
        final T reference = this.reference.get();

        if (!TRACING) {
            return String.format("Managed(%s, %s)", state, reference);
        }

        return toStringTracing(reference, new ArrayList<>(this.traces));
    }

    protected String toStringTracing(final T reference, List<ValidBorrowed<T>> traces) {
        final StringBuilder builder = new StringBuilder();

        builder.append(String.format("Managed(%s, %s:\n", state, reference));

        int i = 0;

        for (final ValidBorrowed<T> b : traces) {
            builder.append(String.format("#%d\n", i++));
            builder.append(TinyStackUtils.formatStack(b.stack()) + "\n");
        }

        builder.append(")");
        return builder.toString();
    }

    protected StackTraceElement[] getStackTrace() {
        if (!CAPTURE_STACK) {
            return EMPTY_STACK;
        }

        final StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        return Arrays.copyOfRange(stack, 0, stack.length - 2);
    }

    @SuppressWarnings("unchecked")
    static <T> Borrowed<T> invalid() {
        return (Borrowed<T>) INVALID;
    }

    protected static class InvalidBorrowed<T> implements Borrowed<T> {
        protected static FutureFinished FINISHED = new FutureFinished() {
            @Override
            public void finished() throws Exception {
            }
        };

        @Override
        public void close() {
        }

        @Override
        public boolean isValid() {
            return false;
        }

        @Override
        public T get() {
            throw new IllegalStateException("cannot get an invalid borrowed reference");
        }

        @Override
        public void release() {
        }

        @Override
        public FutureFinished releasing() {
            return FINISHED;
        }
    }

    /**
     * Wraps returned references that are taken from this SetupOnce instance.
     */
    @RequiredArgsConstructor
    protected static class ValidBorrowed<T> implements Borrowed<T> {
        private final ConcurrentManaged<T> managed;
        private final AsyncFramework async;
        private final T reference;
        protected final StackTraceElement[] stack;

        protected final AtomicBoolean released = new AtomicBoolean(false);

        @Override
        public T get() {
            return reference;
        }

        @Override
        public void release() {
            if (!released.compareAndSet(false, true)) {
                return;
            }

            if (TRACING) {
                managed.traces.remove(this);
            }

            managed.release();
        }

        @Override
        public FutureFinished releasing() {
            return new FutureFinished() {
                @Override
                public void finished() throws Exception {
                    release();
                }
            };
        }

        @Override
        public void close() {
            release();
        }

        /**
         * Implement to log errors on release errors.
         */
        @Override
        protected void finalize() throws Throwable {
            if (released.get()) {
                return;
            }

            async.caller().referenceLeaked(reference, stack);
        }

        @Override
        public boolean isValid() {
            return true;
        }

        public StackTraceElement[] stack() {
            return stack;
        }
    }

    protected static enum ManagedState {
        INITIALIZED, STARTED, STOPPED
    }
}
