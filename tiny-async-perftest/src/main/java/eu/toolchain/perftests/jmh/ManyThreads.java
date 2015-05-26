package eu.toolchain.perftests.jmh;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.openjdk.jmh.annotations.Benchmark;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

import eu.toolchain.async.AsyncFramework;
import eu.toolchain.async.AsyncFuture;
import eu.toolchain.async.TinyAsync;

public class ManyThreads {
    private static final int SIZE = 1000;

    private static int THREAD_COUNT = Runtime.getRuntime().availableProcessors();

    @Benchmark
    public void tiny() throws Exception {
        final ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        final AsyncFramework async = TinyAsync.builder().executor(executor).build();

        final List<AsyncFuture<Integer>> futures = new ArrayList<>();

        for (int i = 0; i < SIZE; i++) {
            final int current = i;

            futures.add(async.call(new Callable<Integer>() {
                @Override
                public Integer call() throws Exception {
                    return current;
                }
            }));
        }

        int sum = 0;

        for (int num : async.collect(futures).get())
            sum += num;

        if (sum != 499500)
            throw new IllegalStateException("did not properly collect all values");

        executor.shutdown();
    }

    @Benchmark
    public void guava() throws Exception {
        final ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        final ListeningExecutorService listeningExecutor = MoreExecutors.listeningDecorator(executor);

        final List<ListenableFuture<Integer>> futures = new ArrayList<>();

        for (int i = 0; i < SIZE; i++) {
            final int current = i;

            futures.add(listeningExecutor.submit(new Callable<Integer>() {
                @Override
                public Integer call() throws Exception {
                    return current;
                }
            }));
        }

        int sum = 0;

        for (int num : Futures.allAsList(futures).get())
            sum += num;

        if (sum != 499500)
            throw new IllegalStateException("did not properly collect all values");

        listeningExecutor.shutdown();
    }
}
