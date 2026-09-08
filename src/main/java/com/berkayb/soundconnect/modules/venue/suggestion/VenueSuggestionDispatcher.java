package com.berkayb.soundconnect.modules.venue.suggestion;

import com.berkayb.soundconnect.shared.mail.producer.MailProducer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Component @Slf4j
public class VenueSuggestionDispatcher {
    private final VenueSuggestionStore store;
    private final VenueSuggestionProperties properties;
    private final MailProducer producer;
    private final Executor executor;
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final AtomicLong lastWarning = new AtomicLong();
    public VenueSuggestionDispatcher(VenueSuggestionStore store, VenueSuggestionProperties properties,
            MailProducer producer, @Qualifier("venueSuggestionDispatchExecutor") Executor executor) {
        this.store = store; this.properties = properties; this.producer = producer; this.executor = executor;
    }
    @Scheduled(fixedDelayString = "${app.venue-suggestions.poll-delay-ms:10000}",
            initialDelayString = "${app.venue-suggestions.initial-delay-ms:15000}")
    public void poll() {
        if (!scheduled.compareAndSet(false, true)) return;
        try {
            executor.execute(() -> {
                try { dispatchBatch(); }
                catch (RuntimeException failure) { warnUnavailable(failure); }
                finally { scheduled.set(false); }
            });
        } catch (RuntimeException rejected) { scheduled.set(false); warnUnavailable(rejected); }
    }
    void dispatchBatch() {
        for (var delivery : store.claimPublishBatch(properties.getDispatchBatchSize())) {
            try { producer.send(delivery.queueRequest()); }
            catch (RuntimeException failure) { store.publishFailed(delivery); continue; }
            store.published(delivery);
        }
    }
    private void warnUnavailable(RuntimeException failure) {
        long now = System.currentTimeMillis(), previous = lastWarning.get();
        if (now - previous >= 60_000 && lastWarning.compareAndSet(previous, now)) {
            log.warn("Venue suggestion outbox unavailable; verify migration and dependencies. exceptionType={}", failure.getClass().getSimpleName());
        }
    }
}
