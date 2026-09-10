package com.berkayb.soundconnect.modules.follow.event;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FollowNotificationDispatcherTest {
    @Test void commitCallbackOnlyQueuesAndTheWorkerRunsOnItsOwnThread() throws Exception {
        var worker=mock(FollowNotificationEventListener.class);
        var executor=new FollowNotificationConfiguration().followNotificationExecutor();
        executor.initialize();
        try {
            var dispatcher=new FollowNotificationDispatcher(worker,executor);
            var completed=new CountDownLatch(1);
            var workerThread=new AtomicReference<Thread>();
            var event=new FollowNotificationRequestedEvent(UUID.randomUUID(),UUID.randomUUID());
            doAnswer(call -> { workerThread.set(Thread.currentThread()); completed.countDown(); return null; })
                    .when(worker).onFollowNotificationRequested(event);
            dispatcher.onFollowNotificationRequested(event);
            assertThat(completed.await(5,TimeUnit.SECONDS)).isTrue();
            assertThat(workerThread.get()).isNotSameAs(Thread.currentThread());
            assertThat(executor.getMaxPoolSize()).isEqualTo(1);
            assertThat(executor.getQueueCapacity()).isEqualTo(128);
        } finally { executor.shutdown(); }
    }

    @Test void saturatedQueueDoesNotExecuteOnCommitThreadOrFailTheCommittedFollow() {
        var worker=mock(FollowNotificationEventListener.class);
        TaskExecutor saturated=task -> { throw new TaskRejectedException("full"); };
        var dispatcher=new FollowNotificationDispatcher(worker,saturated);
        assertThatCode(() -> dispatcher.onFollowNotificationRequested(
                new FollowNotificationRequestedEvent(UUID.randomUUID(),UUID.randomUUID()))).doesNotThrowAnyException();
        verifyNoInteractions(worker);
    }
}
