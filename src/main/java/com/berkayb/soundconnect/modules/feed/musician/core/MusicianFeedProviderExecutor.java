package com.berkayb.soundconnect.modules.feed.musician.core;

import java.util.List;
import java.util.concurrent.*;

/** Bounded provider workers with admission charged to the caller's existing page deadline. */
final class MusicianFeedProviderExecutor extends ThreadPoolExecutor {
    private final ThreadLocal<Boolean> providerWorker = new ThreadLocal<>();

    MusicianFeedProviderExecutor(int parallelism, int queueCapacity, ThreadFactory factory) {
        super(parallelism, parallelism, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), factory, new AbortPolicy());
        // Timed admission can enqueue directly after execute rejects. Keep consumers alive
        // even before the first request, so such work cannot be stranded without a worker.
        prestartAllCoreThreads();
    }

    <T> Future<T> submitBefore(Callable<T> source, long deadlineNanos)
            throws InterruptedException, TimeoutException {
        if (Boolean.TRUE.equals(providerWorker.get())) {
            throw new RejectedExecutionException("Provider workers cannot submit nested feed work");
        }
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        RunnableFuture<T> task = newTaskFor(() -> {
            if (deadlineNanos - System.nanoTime() <= 0) {
                throw new TimeoutException("Feed provider deadline expired before execution");
            }
            return source.call();
        });
        try {
            if (deadlineNanos - System.nanoTime() <= 0) throw admissionTimeout();
            try {
                execute(task);
            } catch (RejectedExecutionException full) {
                if (isShutdown()) throw full;
                long remaining = deadlineNanos - System.nanoTime();
                if (remaining <= 0 || !getQueue().offer(task, remaining, TimeUnit.NANOSECONDS)) {
                    throw admissionTimeout();
                }
                // shutdown/shutdownNow can race with offer, including after the last
                // consumer exits. Never return a future that shutdown stranded in the queue.
                if (isShutdown()) throw new RejectedExecutionException("Feed provider executor shut down");
                if (deadlineNanos - System.nanoTime() <= 0) throw admissionTimeout();
            }
            return task;
        } catch (InterruptedException | TimeoutException | RejectedExecutionException failure) {
            task.cancel(true);
            throw failure;
        }
    }

    private TimeoutException admissionTimeout() {
        return new TimeoutException("Feed provider capacity unavailable before page deadline");
    }

    @Override
    protected <T> RunnableFuture<T> newTaskFor(Callable<T> source) {
        return new FutureTask<>(source) {
            @Override public boolean cancel(boolean mayInterruptIfRunning) {
                boolean cancelled = super.cancel(mayInterruptIfRunning);
                if (cancelled) remove(this);
                return cancelled;
            }
        };
    }

    @Override
    protected <T> RunnableFuture<T> newTaskFor(Runnable task, T result) {
        return newTaskFor(Executors.callable(task, result));
    }

    @Override
    protected void beforeExecute(Thread thread, Runnable task) {
        super.beforeExecute(thread, task);
        providerWorker.set(true);
    }

    @Override
    protected void afterExecute(Runnable task, Throwable failure) {
        providerWorker.remove();
        super.afterExecute(task, failure);
    }

    @Override
    public List<Runnable> shutdownNow() {
        List<Runnable> waiting = super.shutdownNow();
        waiting.forEach(task -> {
            if (task instanceof Future<?> future) future.cancel(false);
        });
        return waiting;
    }
}
