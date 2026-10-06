package com.berkayb.soundconnect.modules.application.mailintent;

import com.berkayb.soundconnect.shared.mail.dto.MailSendRequest;
import com.berkayb.soundconnect.shared.mail.producer.MailProducer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.*;

@Component @Slf4j
public class ApplicationMailDispatcher {
    private final ApplicationMailIntentStore store;
    private final MailProducer producer;
    private final ApplicationMailProperties properties;
    private final Executor executor;
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final AtomicLong lastWarning = new AtomicLong();
    public ApplicationMailDispatcher(ApplicationMailIntentStore store, MailProducer producer,
            ApplicationMailProperties properties, @Qualifier("applicationMailExecutor") Executor executor) {
        this.store=store; this.producer=producer; this.properties=properties; this.executor=executor;
    }
    @Scheduled(fixedDelayString="${app.application-mail.poll-delay-ms:1000}", initialDelayString="${app.application-mail.initial-delay-ms:15000}")
    public void poll() {
        if (!scheduled.compareAndSet(false,true)) return;
        try {
            executor.execute(() -> {
                try { dispatchBatch(); }
                catch (RuntimeException unavailable) { warnUnavailable(unavailable); }
                finally { scheduled.set(false); }
            });
        } catch (RuntimeException rejected) { scheduled.set(false); warnUnavailable(rejected); }
    }
    public void dispatchBatch() {
        for (int i=0; i<properties.getBatchSize(); i++) {
            var next = store.claim();
            if (next.isEmpty()) return;
            var claim=next.get();
            try {
                final MailSendRequest payload;
                try { payload=store.payload(claim); }
                catch (ApplicationMailIntentStore.InvalidSnapshotException invalid) { store.failed(claim,true); continue; }
                try { producer.send(payload); }
                catch (RuntimeException failure) { store.failed(claim,false); continue; }
                // If this outcome cannot commit, leave the lease recoverable. Never infer mailbox delivery.
                store.published(claim);
            } catch (RuntimeException outcomeUnknown) {
                log.warn("Application mail outcome unavailable. intentId={}, applicationId={}, exceptionType={}",
                        claim.id(),claim.applicationId(),outcomeUnknown.getClass().getSimpleName());
                // A broken individual row/outcome cannot starve the remaining bounded batch.
            }
        }
    }
    private void warnUnavailable(RuntimeException failure) {
        long now=System.currentTimeMillis(), previous=lastWarning.get();
        if (now-previous>=60000 && lastWarning.compareAndSet(previous,now))
            log.warn("Application mail storage unavailable; verify migration/dependencies. exceptionType={}",failure.getClass().getSimpleName());
    }
}
