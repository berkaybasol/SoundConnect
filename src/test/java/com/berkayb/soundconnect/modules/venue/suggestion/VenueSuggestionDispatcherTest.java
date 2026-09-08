package com.berkayb.soundconnect.modules.venue.suggestion;

import com.berkayb.soundconnect.shared.mail.producer.MailProducer;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class VenueSuggestionDispatcherTest {
    @Test void recipientPublishFailureDoesNotForgetOtherRecipientOrMarkFailedRecipientQueued() {
        var store = mock(VenueSuggestionStore.class); var producer = mock(MailProducer.class);
        var first = delivery(); var second = delivery();
        when(store.claimPublishBatch(5)).thenReturn(List.of(first, second));
        doThrow(new IllegalStateException("broker failure")).when(producer).send(first.queueRequest());
        new VenueSuggestionDispatcher(store, new VenueSuggestionProperties(), producer, Runnable::run).dispatchBatch();
        verify(store).publishFailed(first); verify(store, never()).published(first);
        verify(producer).send(second.queueRequest()); verify(store).published(second);
    }
    @Test void pollNeverBuildsAnUnboundedProcessLocalQueueAndResetsAfterFailure() {
        var store = mock(VenueSuggestionStore.class); var producer = mock(MailProducer.class);
        var tasks = new ArrayList<Runnable>();
        var dispatcher = new VenueSuggestionDispatcher(store, new VenueSuggestionProperties(), producer, tasks::add);
        for (int i = 0; i < 100; i++) dispatcher.poll();
        assertThat(tasks).hasSize(1);
        when(store.claimPublishBatch(5)).thenThrow(new IllegalStateException("migration absent"));
        tasks.getFirst().run(); dispatcher.poll();
        assertThat(tasks).hasSize(2);
    }
    @Test void saturatedExecutorDoesNotLeaveDispatcherPermanentlyDisabled() {
        var attempts = new AtomicInteger(); var store = mock(VenueSuggestionStore.class);
        var dispatcher = new VenueSuggestionDispatcher(store, new VenueSuggestionProperties(), mock(MailProducer.class),
                task -> { attempts.incrementAndGet(); throw new RejectedExecutionException(); });
        dispatcher.poll(); dispatcher.poll();
        assertThat(attempts.get()).isEqualTo(2);
        verifyNoInteractions(store);
    }
    @Test void publicHealthContainsOnlySafeCountsAndMissingMigrationIsActionable() {
        var store = mock(VenueSuggestionStore.class);
        when(store.healthCounts()).thenReturn(java.util.Map.of("review", 1L, "pending", 2L, "stale", 1L));
        var health = new VenueSuggestionHealthIndicator(store).health();
        assertThat(health.getStatus().getCode()).isEqualTo("DEGRADED");
        assertThat(health.getDetails()).containsOnlyKeys("review", "pending", "stale");
        when(store.healthCounts()).thenThrow(new IllegalStateException("private details"));
        assertThat(new VenueSuggestionHealthIndicator(store).health().getDetails())
                .containsOnlyKeys("reason").containsEntry("reason", "storage_or_migration_unavailable");
    }
    private VenueSuggestionStore.Delivery delivery() {
        return new VenueSuggestionStore.Delivery(UUID.randomUUID(), UUID.randomUUID(), "admin@example.test", "Subject", "Text");
    }
}
