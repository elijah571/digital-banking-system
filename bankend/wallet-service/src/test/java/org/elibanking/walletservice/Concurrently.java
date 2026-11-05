package org.elibanking.walletservice;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Runs calls at the same moment, each on its own thread, for the race tests. Each outcome is the call's result, or the
 * exception it threw.
 */
public final class Concurrently {

    private Concurrently() {
    }

    /** The same call, {@code parallel} times at once. */
    public static List<Object> sameCall(int parallel, Callable<Object> call) throws Exception {
        List<Callable<Object>> calls = new ArrayList<>();
        for (int i = 0; i < parallel; i++) {
            calls.add(call);
        }
        return together(calls);
    }

    @SafeVarargs
    public static List<Object> together(Callable<Object>... calls) throws Exception {
        return together(List.of(calls));
    }

    public static List<Object> together(List<Callable<Object>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        CyclicBarrier start = new CyclicBarrier(calls.size());
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> call : calls) {
                futures.add(pool.submit(() -> {
                    start.await(30, TimeUnit.SECONDS);
                    try {
                        return call.call();
                    } catch (Exception e) {
                        return e;
                    }
                }));
            }
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> future : futures) {
                outcomes.add(future.get(60, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    public static long count(List<Object> outcomes, Class<?> type) {
        return outcomes.stream().filter(type::isInstance).count();
    }
}
