package com.berkayb.soundconnect.modules.message.dm.event;

import com.berkayb.soundconnect.modules.notification.service.AfterCommitDeliveryExecutor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class DmMessageEventDispatcher {
    private final DmMessageEventListener worker;
    private final AfterCommitDeliveryExecutor executor;
    @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT)
    public void onDmMessageSent(DmMessageSentEvent event) { executor.submit(() -> worker.onDmMessageSent(event)); }
    @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT)
    public void onDmMessageRead(DmMessageReadEvent event) { executor.submit(() -> worker.onDmMessageRead(event)); }
}
