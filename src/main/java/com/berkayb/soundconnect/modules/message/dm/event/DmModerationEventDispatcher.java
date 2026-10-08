package com.berkayb.soundconnect.modules.message.dm.event;

import com.berkayb.soundconnect.modules.notification.service.AfterCommitDeliveryExecutor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class DmModerationEventDispatcher {
    private final DmModerationProjection projection;
    private final AfterCommitDeliveryExecutor executor;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onModerated(DmModeratedEvent event) {
        // The committing thread still owns its connection during this callback.
        // Dispatch first so the fresh projection transaction cannot starve the
        // same pool while every moderation request waits for a second connection.
        executor.submit(() -> projection.refresh(event));
    }
}
