package com.berkayb.soundconnect.modules.follow.event;

import com.berkayb.soundconnect.modules.follow.band.event.BandFollowNotificationRequestedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Never borrows another connection on the thread committing the follow. */
@Component
@Slf4j
public class FollowNotificationDispatcher {
    private final FollowNotificationEventListener worker;
    private final TaskExecutor executor;

    public FollowNotificationDispatcher(FollowNotificationEventListener worker,
            @Qualifier("followNotificationExecutor") TaskExecutor executor) {
        this.worker = worker;
        this.executor = executor;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onFollowNotificationRequested(FollowNotificationRequestedEvent event) {
        if (event != null && event.followerId() != null && event.followingId() != null)
            submit(() -> worker.onFollowNotificationRequested(event));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBandFollowNotificationRequested(BandFollowNotificationRequestedEvent event) {
        if (event != null && event.followerId() != null && event.bandId() != null && !event.recipientIds().isEmpty())
            submit(() -> worker.onBandFollowNotificationRequested(event));
    }

    private void submit(Runnable task) {
        try {
            executor.execute(() -> {
                try { task.run(); }
                catch (RuntimeException failed) {
                    log.warn("Best-effort follow notification failed in worker. exceptionType={}", failed.getClass().getSimpleName());
                }
            });
        } catch (TaskRejectedException saturated) {
            // Delivery was already best effort. Do not run on the commit thread:
            // its transaction resources have not yet been released.
            log.warn("Best-effort follow notification skipped: bounded worker queue is full or stopping");
        }
    }
}
