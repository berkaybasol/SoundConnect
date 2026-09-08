package com.berkayb.soundconnect.modules.venue.suggestion;

import com.berkayb.soundconnect.shared.mail.adapter.MailSenderClient;
import com.berkayb.soundconnect.shared.mail.dto.MailSendRequest;
import com.berkayb.soundconnect.shared.mail.helper.MailJobHelper;
import com.berkayb.soundconnect.shared.mail.producer.MailRetryPublisher;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import java.util.Optional;
import java.util.UUID;
import java.util.Map;

/** Only VENUE_SUGGESTION_ADMIN is routed here; all other existing mail kinds retain their existing behavior. */
@Component @RequiredArgsConstructor @Slf4j
public class VenueSuggestionMailDelivery {
    private final VenueSuggestionStore store;
    private final MailSenderClient sender;
    private final MailJobHelper helper;
    private final MailRetryPublisher retryPublisher;

    public void consume(MailSendRequest untrustedQueueMessage, long tag, Map<String, Object> headers, Channel channel) {
        final UUID id;
        try {
            var raw = untrustedQueueMessage.params() == null ? null
                    : untrustedQueueMessage.params().get("venueSuggestionDeliveryId");
            if (!(raw instanceof String value)) throw new IllegalArgumentException("Missing durable delivery id");
            id = UUID.fromString(value);
        } catch (RuntimeException malformed) {
            log.error("Venue suggestion mail rejected: invalid durable delivery reference");
            reject(channel, tag, false); return;
        }
        final Optional<VenueSuggestionStore.Delivery> claimed;
        try { claimed = store.claimSend(id); }
        catch (RuntimeException unavailable) {
            log.warn("Venue suggestion mail claim unavailable. deliveryId={}, exceptionType={}", id, unavailable.getClass().getSimpleName());
            int attempt = helper.retryAttempt(headers);
            if (attempt >= 20) { reject(channel, tag, false); return; }
            try {
                retryPublisher.publishWithDelay(untrustedQueueMessage, 30_000, attempt + 1, "venue-suggestion-storage");
                ack(channel, tag);
            } catch (RuntimeException retryUnavailable) {
                // The durable outbox watchdog recovers this job. Do not hot-requeue on a DB/broker outage.
                reject(channel, tag, false);
            }
            return;
        }
        if (claimed.isEmpty()) { ack(channel, tag); return; }
        var delivery = claimed.get();
        // Destination, subject and text are reconstructed from durable server-owned data.
        // Queue-supplied to/subject/html/text are deliberately ignored.
        try {
            sender.send(delivery.recipient(), delivery.subject(), delivery.text(), null);
        } catch (HttpStatusCodeException httpFailure) {
            try {
                if (httpFailure.getStatusCode().value() == 429) {
                    store.retryRateLimited(delivery, helper.retryAfterSeconds(httpFailure).orElse(60L));
                } else {
                    store.needsReview(delivery, "provider_rejected_or_unknown");
                    log.error("Venue suggestion mail needs review after provider response. deliveryId={}, status={}",
                            id, httpFailure.getStatusCode().value());
                }
            } catch (RuntimeException stateFailure) { stateFailure(id, stateFailure); }
            ack(channel, tag); return;
        } catch (RuntimeException ambiguousFailure) {
            // An I/O failure or timeout cannot prove the provider did not accept the email.
            try { store.needsReview(delivery, "send_outcome_unknown"); }
            catch (RuntimeException stateFailure) { stateFailure(id, stateFailure); }
            log.error("Venue suggestion mail outcome is unknown; automatic resend suppressed. deliveryId={}, exceptionType={}",
                    id, ambiguousFailure.getClass().getSimpleName());
            ack(channel, tag); return;
        }
        try { store.sent(delivery); }
        catch (RuntimeException stateFailure) {
            // SENDING was committed before the provider call. Its expiry parks for review, never sends again.
            stateFailure(id, stateFailure);
        }
        ack(channel, tag);
    }

    private static void stateFailure(UUID id, RuntimeException failure) {
        log.error("Venue suggestion delivery outcome could not be recorded; durable send fence retained. deliveryId={}, exceptionType={}",
                id, failure.getClass().getSimpleName());
    }
    private static void ack(Channel channel, long tag) {
        try { channel.basicAck(tag, false); }
        catch (Exception failure) { log.warn("Venue suggestion mail ACK failed; durable fence protects redelivery"); }
    }
    private static void reject(Channel channel, long tag, boolean requeue) {
        try { channel.basicReject(tag, requeue); }
        catch (Exception failure) { log.warn("Venue suggestion mail reject failed. requeue={}", requeue); }
    }
}
