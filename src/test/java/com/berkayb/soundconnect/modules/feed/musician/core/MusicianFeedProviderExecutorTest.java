package com.berkayb.soundconnect.modules.feed.musician.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MusicianFeedProviderExecutorTest {
    private final List<ExecutorService> executors = new ArrayList<>();

    @AfterEach
    void stopWorkers() throws InterruptedException {
        for (ExecutorService executor : executors) executor.shutdownNow();
        for (ExecutorService executor : executors) {
            assertThat(executor.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void sixteenPagesOfThreeProvidersWaitForCapacityWithoutGrowingWorkersOrQueue() throws Exception {
        MusicianFeedProviderExecutor pool = pool(6, 24);
        ExecutorService callers = Executors.newFixedThreadPool(16);
        executors.add(callers);
        CountDownLatch start = new CountDownLatch(1), sixRunning = new CountDownLatch(6), release = new CountDownLatch(1);
        AtomicInteger completed = new AtomicInteger(), active = new AtomicInteger(), maximumActive = new AtomicInteger();
        List<Future<List<Integer>>> pages = new ArrayList<>();
        try {
            for (int page = 0; page < 16; page++) {
                pages.add(callers.submit(() -> {
                    start.await();
                    long deadline = deadlineAfter(3, TimeUnit.SECONDS);
                    List<Future<Integer>> providers = new ArrayList<>();
                    for (int provider = 0; provider < 3; provider++) {
                        providers.add(pool.submitBefore(() -> {
                            assertThat(Thread.currentThread().getName()).startsWith("feed-test-provider-");
                            maximumActive.accumulateAndGet(active.incrementAndGet(), Math::max);
                            sixRunning.countDown();
                            try {
                                assertThat(release.await(2, TimeUnit.SECONDS)).isTrue();
                                return completed.incrementAndGet();
                            } finally {
                                active.decrementAndGet();
                            }
                        }, deadline));
                    }
                    List<Integer> values = new ArrayList<>();
                    for (Future<Integer> provider : providers) values.add(provider.get(3, TimeUnit.SECONDS));
                    return values;
                }));
            }
            start.countDown();
            assertThat(sixRunning.await(1, TimeUnit.SECONDS)).isTrue();
            awaitCondition(() -> pool.getQueue().size() == 24);
            assertThat(pool.getPoolSize()).isEqualTo(6);
            assertThat(pool.getQueue().remainingCapacity()).isZero();
            release.countDown();
            List<Integer> results = new ArrayList<>();
            for (Future<List<Integer>> page : pages) results.addAll(page.get(4, TimeUnit.SECONDS));
            assertThat(results).hasSize(48).doesNotHaveDuplicates();
            assertThat(completed.get()).isEqualTo(48);
            assertThat(maximumActive.get()).isEqualTo(6);
            assertThat(pool.getLargestPoolSize()).isEqualTo(6);
            assertThat(pool.getQueue()).isEmpty();
            assertThat(pool.getQueue().remainingCapacity()).isEqualTo(24);
        } finally {
            release.countDown();
        }
    }

    @Test
    void admissionUsesTheOriginalDeadlineAndDoesNotEnqueueAfterExpiry() throws Exception {
        MusicianFeedProviderExecutor pool = pool(1, 1);
        CountDownLatch release = occupyWorker(pool);
        AtomicInteger calls = new AtomicInteger();
        long deadline = deadlineAfter(80, TimeUnit.MILLISECONDS);
        Future<?> queued = pool.submitBefore(calls::incrementAndGet, deadline);
        try {
            assertThatThrownBy(() -> pool.submitBefore(calls::incrementAndGet, deadline))
                    .isInstanceOf(TimeoutException.class);
            assertThatThrownBy(() -> pool.submitBefore(calls::incrementAndGet, deadline))
                    .isInstanceOf(TimeoutException.class);
            assertThat(pool.getQueue()).containsExactly((Runnable) queued);
            assertThat(calls.get()).isZero();
            release.countDown();
            assertThatThrownBy(() -> queued.get(1, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(TimeoutException.class);
            assertThat(calls.get()).isZero();
        } finally {
            release.countDown();
        }
    }

    @Test
    void cancellingQueuedWorkImmediatelyMakesRoomWithoutInterruptingRunningWork() throws Exception {
        MusicianFeedProviderExecutor pool = pool(1, 1);
        CountDownLatch release = occupyWorker(pool);
        Future<?> queued = pool.submit(() -> { throw new AssertionError("Cancelled work executed"); });
        assertThat(queued.cancel(true)).isTrue();
        assertThat(pool.getQueue()).isEmpty();
        Future<String> replacement = pool.submitBefore(() -> "replacement", deadlineAfter(2, TimeUnit.SECONDS));
        assertThat(pool.getActiveCount()).isEqualTo(1);
        release.countDown();
        assertThat(replacement.get(1, TimeUnit.SECONDS)).isEqualTo("replacement");
    }

    @Test
    void interruptedAdmissionDoesNotLeakAQueuedTask() throws Exception {
        MusicianFeedProviderExecutor pool = pool(1, 1);
        CountDownLatch release = occupyWorker(pool);
        Future<?> queued = pool.submit(() -> { });
        AtomicReference<Throwable> result = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                pool.submitBefore(() -> "never", deadlineAfter(2, TimeUnit.SECONDS));
                result.set(new AssertionError("Admission should have been interrupted"));
            } catch (Throwable failure) {
                result.set(failure);
            }
        });
        try {
            caller.start();
            awaitCondition(() -> caller.getState() == Thread.State.TIMED_WAITING);
            caller.interrupt();
            caller.join(1_000);
            assertThat(caller.isAlive()).isFalse();
            assertThat(result.get()).isInstanceOf(InterruptedException.class);
            assertThat(pool.getQueue()).containsExactly((Runnable) queued);
        } finally {
            release.countDown();
            caller.interrupt();
            caller.join(1_000);
        }
    }

    @Test
    void shutdownDuringAdmissionRejectsAndCancelsDrainedFutures() throws Exception {
        MusicianFeedProviderExecutor pool = pool(1, 1);
        occupyWorker(pool);
        Future<?> queued = pool.submit(() -> { });
        AtomicReference<Throwable> result = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                pool.submitBefore(() -> "never", deadlineAfter(2, TimeUnit.SECONDS));
                result.set(new AssertionError("Shut down executor admitted work"));
            } catch (Throwable failure) {
                result.set(failure);
            }
        });
        try {
            caller.start();
            awaitCondition(() -> caller.getState() == Thread.State.TIMED_WAITING);
            pool.shutdownNow();
            caller.join(1_000);
            assertThat(caller.isAlive()).isFalse();
            assertThat(result.get()).isInstanceOf(RejectedExecutionException.class);
            assertThat(queued.isCancelled()).isTrue();
            assertThat(pool.getQueue()).isEmpty();
            assertThatThrownBy(() -> pool.submitBefore(() -> "never", deadlineAfter(1, TimeUnit.SECONDS)))
                    .isInstanceOf(RejectedExecutionException.class);
        } finally {
            caller.interrupt();
            caller.join(1_000);
        }
    }

    @Test
    void providerWorkerCannotRecursivelySubmitEvenWhenQueueHasRoom() throws Exception {
        MusicianFeedProviderExecutor pool = pool(1, 1);
        pool.submitBefore(() -> {
            assertThatThrownBy(() -> pool.submitBefore(() -> "nested", deadlineAfter(1, TimeUnit.SECONDS)))
                    .isInstanceOf(RejectedExecutionException.class);
            return null;
        }, deadlineAfter(1, TimeUnit.SECONDS)).get(1, TimeUnit.SECONDS);
        assertThat(pool.getQueue()).isEmpty();
    }

    private MusicianFeedProviderExecutor pool(int workers, int capacity) {
        AtomicInteger sequence = new AtomicInteger();
        MusicianFeedProviderExecutor pool = new MusicianFeedProviderExecutor(workers, capacity,
                task -> new Thread(task, "feed-test-provider-" + sequence.incrementAndGet()));
        executors.add(pool);
        return pool;
    }

    private CountDownLatch occupyWorker(MusicianFeedProviderExecutor pool) throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        pool.submit(() -> {
            started.countDown();
            release.await();
            return null;
        });
        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        return release;
    }

    private static long deadlineAfter(long value, TimeUnit unit) {
        return System.nanoTime() + unit.toNanos(value);
    }

    private static void awaitCondition(BooleanSupplier condition) {
        long deadline = deadlineAfter(1, TimeUnit.SECONDS);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) LockSupport.parkNanos(1_000_000);
        assertThat(condition.getAsBoolean()).isTrue();
    }
}
