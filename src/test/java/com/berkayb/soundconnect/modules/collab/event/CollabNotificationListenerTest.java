package com.berkayb.soundconnect.modules.collab.event;

import com.berkayb.soundconnect.modules.collab.outbox.CollabNotificationDispatchCoordinator;
import com.berkayb.soundconnect.modules.collab.outbox.CollabNotificationOutboxDispatcher;
import com.berkayb.soundconnect.modules.collab.outbox.CollabNotificationOutboxService;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class CollabNotificationListenerTest {

    @Test
    void persistsBeforeCommitAndDispatchesTheStableEventIdAfterCommit() throws Exception {
        CollabNotificationOutboxService outboxService = mock(CollabNotificationOutboxService.class);
        CollabNotificationOutboxDispatcher dispatcher = mock(CollabNotificationOutboxDispatcher.class);
        Executor directExecutor = Runnable::run;
        CollabNotificationDispatchCoordinator coordinator =
                new CollabNotificationDispatchCoordinator(directExecutor);
        CollabNotificationListener listener = new CollabNotificationListener(
                outboxService,
                dispatcher,
                coordinator
        );
        CollabNotificationEvent event = event();

        listener.persistInDomainTransaction(event);
        listener.onCollabNotification(event);

        verify(outboxService).enqueue(event);
        verify(dispatcher).dispatch(event.eventId());

        TransactionalEventListener beforeCommit = CollabNotificationListener.class
                .getMethod("persistInDomainTransaction", CollabNotificationEvent.class)
                .getAnnotation(TransactionalEventListener.class);
        assertThat(beforeCommit).isNotNull();
        assertThat(beforeCommit.phase()).isEqualTo(TransactionPhase.BEFORE_COMMIT);
        assertThat(beforeCommit.fallbackExecution()).isFalse();

        TransactionalEventListener afterCommit = CollabNotificationListener.class
                .getMethod("onCollabNotification", CollabNotificationEvent.class)
                .getAnnotation(TransactionalEventListener.class);
        assertThat(afterCommit).isNotNull();
        assertThat(afterCommit.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(afterCommit.fallbackExecution()).isFalse();
    }

    @Test
    void saturatedImmediateExecutorDoesNotEscapeAfterCommit() {
        CollabNotificationOutboxService outboxService = mock(CollabNotificationOutboxService.class);
        CollabNotificationOutboxDispatcher dispatcher = mock(CollabNotificationOutboxDispatcher.class);
        Executor saturatedExecutor = command -> {
            throw new RejectedExecutionException("full");
        };
        CollabNotificationDispatchCoordinator coordinator =
                new CollabNotificationDispatchCoordinator(saturatedExecutor);
        CollabNotificationListener listener = new CollabNotificationListener(
                outboxService,
                dispatcher,
                coordinator
        );

        assertThatCode(() -> listener.onCollabNotification(event()))
                .doesNotThrowAnyException();
        assertThat(coordinator.scheduledCount()).isZero();
    }

    @Test
    void duplicateAfterCommitCallbacksShareOneQueuedTaskAndReleaseFenceOnCompletion() {
        CollabNotificationOutboxService outboxService = mock(CollabNotificationOutboxService.class);
        CollabNotificationOutboxDispatcher dispatcher = mock(CollabNotificationOutboxDispatcher.class);
        QueuedExecutor executor = new QueuedExecutor();
        CollabNotificationDispatchCoordinator coordinator =
                new CollabNotificationDispatchCoordinator(executor);
        CollabNotificationListener listener = new CollabNotificationListener(
                outboxService,
                dispatcher,
                coordinator
        );
        CollabNotificationEvent event = event();

        listener.onCollabNotification(event);
        listener.onCollabNotification(event);

        assertThat(coordinator.scheduledCount()).isOne();
        assertThat(executor.size()).isOne();
        executor.runNext();
        verify(dispatcher).dispatch(event.eventId());
        assertThat(coordinator.scheduledCount()).isZero();
    }

    private static CollabNotificationEvent event() {
        return CollabNotificationEvent.create(
                UUID.randomUUID(),
                NotificationType.COLLAB_APPLICATION_RECEIVED,
                "Yeni basvuru",
                "Ilanina yeni bir basvuru geldi.",
                "APPLICATION_RECEIVED",
                Map.of("listingId", UUID.randomUUID()),
                Instant.parse("2026-08-11T00:00:00Z")
        );
    }

    private static final class QueuedExecutor implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        int size() {
            return tasks.size();
        }

        void runNext() {
            tasks.remove().run();
        }
    }
}
