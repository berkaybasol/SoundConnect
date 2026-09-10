package com.berkayb.soundconnect.modules.venue.suggestion;

import com.berkayb.soundconnect.shared.mail.adapter.MailSenderClient;
import com.berkayb.soundconnect.shared.mail.consumer.MailJobConsumer;
import com.berkayb.soundconnect.shared.mail.dto.MailSendRequest;
import com.berkayb.soundconnect.shared.mail.enums.MailKind;
import com.berkayb.soundconnect.shared.mail.helper.MailJobHelper;
import com.berkayb.soundconnect.shared.mail.producer.MailRetryPublisher;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import static org.mockito.Mockito.*;

class VenueSuggestionMailDeliveryTest {
    VenueSuggestionStore store;
    MailSenderClient sender;
    MailJobHelper helper;
    MailRetryPublisher retries;
    VenueSuggestionMailDelivery handler;
    Channel channel;
    VenueSuggestionStore.Delivery delivery = new VenueSuggestionStore.Delivery(UUID.randomUUID(), UUID.randomUUID(),
            "admin@example.test", "Server subject", "Server body");
    MailSendRequest request;
    @BeforeEach void setUp() {
        store = mock(VenueSuggestionStore.class); sender = mock(MailSenderClient.class);
        helper = mock(MailJobHelper.class); retries = mock(MailRetryPublisher.class); channel = mock(Channel.class);
        handler = new VenueSuggestionMailDelivery(store, sender, helper, retries);
        request = new MailSendRequest("attacker@example.test", "Forged subject", "<script>forged</script>",
                "Forged body", MailKind.VENUE_SUGGESTION_ADMIN, Map.of("venueSuggestionDeliveryId", delivery.id().toString()));
        when(store.claimSend(delivery.id())).thenReturn(Optional.of(delivery));
    }
    @Test void reconstructsAllMailFieldsFromDurableServerDataAndRecordsSentBeforeAck() throws Exception {
        handler.consume(request, 1, Map.of(), channel);
        var ordered = inOrder(store, sender, channel);
        ordered.verify(store).claimSend(delivery.id());
        ordered.verify(sender).send(delivery.recipient(), delivery.subject(), delivery.text(), null);
        ordered.verify(store).sent(delivery);
        ordered.verify(channel).basicAck(1, false);
        verifyNoInteractions(retries);
    }
    @Test void duplicateOrAlreadySendingDeliveryDoesNotCallProvider() throws Exception {
        when(store.claimSend(delivery.id())).thenReturn(Optional.empty());
        handler.consume(request, 1, Map.of(), channel);
        verifyNoInteractions(sender, retries);
        verify(channel).basicAck(1, false);
    }
    @Test void ambiguousNetworkFailureParksAndNeverAutomaticallyResends() throws Exception {
        doThrow(new ResourceAccessException("Unknown provider acceptance")).when(sender).send(any(), any(), any(), any());
        handler.consume(request, 1, Map.of(), channel);
        verify(store).needsReview(delivery, "send_outcome_unknown");
        verify(store, never()).sent(any()); verify(store, never()).retryRateLimited(any(), anyLong());
        verifyNoInteractions(retries); verify(channel).basicAck(1, false);
    }
    @Test void confirmed429RetriesWithDurableStateAndRetryAfter() throws Exception {
        var response = new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS);
        when(helper.retryAfterSeconds(response)).thenReturn(Optional.of(120L));
        doThrow(response).when(sender).send(any(), any(), any(), any());
        handler.consume(request, 1, Map.of(), channel);
        verify(store).retryRateLimited(delivery, 120);
        verify(store, never()).needsReview(any(), any()); verify(channel).basicAck(1, false);
    }
    @Test void definitiveProviderRejectionIsDurableReviewNotDropped() throws Exception {
        doThrow(new HttpClientErrorException(HttpStatus.UNPROCESSABLE_ENTITY)).when(sender).send(any(), any(), any(), any());
        handler.consume(request, 1, Map.of(), channel);
        verify(store).needsReview(delivery, "provider_rejected_or_unknown");
        verify(channel).basicAck(1, false);
    }
    @Test void sentStateWriteFailureKeepsPrecommittedFenceWithoutCallingProviderAgain() throws Exception {
        doThrow(new DataAccessResourceFailureException("offline")).when(store).sent(delivery);
        handler.consume(request, 1, Map.of(), channel);
        verify(sender, times(1)).send(any(), any(), any(), any());
        verifyNoInteractions(retries); verify(channel).basicAck(1, false);
    }
    @Test void databaseOutageUsesDelayedRetryInsteadOfHotRequeue() throws Exception {
        when(store.claimSend(delivery.id())).thenThrow(new DataAccessResourceFailureException("offline"));
        when(helper.retryAttempt(Map.of())).thenReturn(2);
        handler.consume(request, 1, Map.of(), channel);
        verify(retries).publishWithDelay(request, 30_000, 3, "venue-suggestion-storage");
        verify(channel).basicAck(1, false); verifyNoInteractions(sender);
    }
    @Test void databaseAndRetryPublisherOutageFallsBackToDurableOutboxNotHotRequeue() throws Exception {
        when(store.claimSend(delivery.id())).thenThrow(new DataAccessResourceFailureException("offline"));
        doThrow(new IllegalStateException("broker offline")).when(retries).publishWithDelay(any(), anyLong(), anyInt(), any());
        handler.consume(request, 1, Map.of(), channel);
        verify(channel).basicReject(1, false); verifyNoInteractions(sender);
    }
    @Test void excessiveDatabaseRedeliveriesAreBounded() throws Exception {
        when(store.claimSend(delivery.id())).thenThrow(new DataAccessResourceFailureException("offline"));
        when(helper.retryAttempt(Map.of())).thenReturn(20);
        handler.consume(request, 1, Map.of(), channel);
        verify(channel).basicReject(1, false); verifyNoInteractions(sender, retries);
    }
    @Test void invalidDurableReferenceCannotSendQueuedArbitraryMail() throws Exception {
        var malformed = new MailSendRequest("attacker@example.test", "subject", null, "text", MailKind.VENUE_SUGGESTION_ADMIN, Map.of());
        handler.consume(malformed, 1, Map.of(), channel);
        verifyNoInteractions(store, sender, retries); verify(channel).basicReject(1, false);
    }
    @Test void sharedConsumerNewKindCannotFallBackToGenericRedisSender() throws Exception {
        var generic = new MailJobConsumer(sender, helper, retries, mock(com.berkayb.soundconnect.modules.notification.service.NotificationMailDelivery.class));
        var deliveryHandler = mock(VenueSuggestionMailDelivery.class);
        generic.setVenueSuggestionDelivery(deliveryHandler);
        generic.listenMailJobs(request, 1, Map.of(), channel);
        verify(deliveryHandler).consume(request, 1, Map.of(), channel);
        verifyNoInteractions(sender, helper, retries);
    }
    @Test void missingNewKindHandlerFailsClosedAndLeavesDurableWatchdogResponsible() throws Exception {
        var generic = new MailJobConsumer(sender, helper, retries, mock(com.berkayb.soundconnect.modules.notification.service.NotificationMailDelivery.class));
        generic.listenMailJobs(request, 1, Map.of(), channel);
        verify(channel).basicReject(1, false); verifyNoInteractions(sender, helper, retries);
    }
}
