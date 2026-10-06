package com.berkayb.soundconnect.modules.notification.dlq;

import com.rabbitmq.client.*;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j @Service
public class NotificationDlqOperations {
    public record Summary(String status, Integer readyMessages, Long unackedMessages, Long totalMessages,
                          Instant measuredAt, boolean stale, NotificationDlqMessage.Age oldest,
                          boolean replayEnabled, int window) { }
    public record Selection(UUID eventId, String fingerprint) { }
    public record Result(UUID operationId, String outcome, List<NotificationDlqMessage.Metadata> messages,
                         int examined, String brokerAcceptance, String sourceAck, String consumerOutcome) { }
    private record Observation(Integer ready, Instant measuredAt) { }
    private final NotificationDlqBroker broker;
    private final NotificationDlqProperties config;
    private final String queue, sourceQueue, exchange, routingKey, scope;
    private final MeterRegistry metrics;
    private Future<Observation> pending;
    private Observation observation;
    private String previousStatus;

    public NotificationDlqOperations(NotificationDlqBroker broker, NotificationDlqProperties config, MeterRegistry metrics,
            @Value("${app.messaging.notification.dlq:notification.queue.dlq}") String queue,
            @Value("${app.messaging.notification.queue:notification.queue}") String sourceQueue,
            @Value("${app.messaging.notification.exchange:notification.exchange}") String exchange,
            @Value("${app.messaging.notification.publishRoutingKey:notification.event}") String routingKey) {
        this.broker = broker; this.config = config; this.metrics = metrics;
        this.queue = queue; this.sourceQueue = sourceQueue; this.exchange = exchange; this.routingKey = routingKey;
        for (String name : List.of(queue, sourceQueue, exchange, routingKey))
            if (name.isBlank() || name.length() > 255) throw new IllegalArgumentException("Invalid notification topology");
        if (queue.equals(sourceQueue)) throw new IllegalArgumentException("DLQ must differ from ingress");
        scope = broker.virtualHost() + "\n" + queue;
        metrics.gauge("notification.dlq.ready", this, o -> { var s = o.summary(); return s.readyMessages == null ? Double.NaN : s.readyMessages; });
        metrics.gauge("notification.dlq.available", this, o -> o.summary().readyMessages == null ? 0 : 1);
    }

    @Scheduled(fixedDelayString = "${app.messaging.notification.dlq-ops.interval-ms:30000}", initialDelay = 0)
    public synchronized void observe() {
        harvest();
        if (pending != null) return;
        try {
            pending = broker.submit(true, s -> new Observation(s.channel.queueDeclarePassive(queue).getMessageCount(), Instant.now()));
        } catch (RejectedExecutionException e) { observation = new Observation(null, Instant.now()); }
    }
    private void harvest() {
        if (pending == null || !pending.isDone()) return;
        try { observation = pending.get(); }
        catch (Exception e) { observation = new Observation(null, Instant.now()); if (e instanceof InterruptedException) Thread.currentThread().interrupt(); }
        finally { pending = null; }
    }
    public synchronized Summary summary() {
        harvest();
        Instant now = Instant.now();
        boolean stale = observation == null || Duration.between(observation.measuredAt, now).toMillis() > config.getStaleMs();
        Integer ready = stale || observation == null ? null : observation.ready;
        String status = stale ? "UNKNOWN" : ready == null ? "UNAVAILABLE" : ready > 0 ? "DEGRADED" : "UP";
        if (!status.equals(previousStatus)) { log.info("Notification DLQ observation status={}", status); previousStatus = status; }
        return new Summary(status, ready, null, null, observation == null ? null : observation.measuredAt, stale,
                new NotificationDlqMessage.Age(null, "UNKNOWN", "NONE", "PASSIVE_QUEUE_INFO_HAS_NO_AGE", observation == null ? null : observation.measuredAt),
                config.isReplayEnabled(), config.getWindow());
    }

    public Result inspect(UUID actor) { return execute(actor, null); }
    public Result replay(UUID actor, Selection selection) {
        Objects.requireNonNull(selection); Objects.requireNonNull(selection.eventId);
        if (selection.fingerprint == null || !selection.fingerprint.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid selection");
        return execute(actor, selection);
    }
    private Result execute(UUID actor, Selection selection) {
        UUID operation = UUID.randomUUID();
        String action = selection == null ? "INSPECT" : "REPLAY";
        log.info("Notification DLQ audit actor={} operation={} action={} state=STARTED", actor, operation, action);
        Result result;
        if (selection != null && !config.isReplayEnabled()) result = result(operation, "DISABLED", List.of(), 0, "NOT_ATTEMPTED", "NOT_SENT");
        else {
            Future<Result> work = null;
            try {
                work = broker.submit(false, session -> window(session, operation, selection));
                result = work.get(config.getDeadlineMs() + config.getRpcTimeoutMs(), TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException e) { result = result(operation, "BUSY_OR_STOPPED", List.of(), 0, "NOT_ATTEMPTED", "NOT_SENT"); }
            catch (Exception e) {
                if (work != null) work.cancel(true);
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                // No claim that cancellation prevented an in-flight publish/ACK.
                result = result(operation, "UNAVAILABLE_OR_AMBIGUOUS", List.of(), 0, "UNKNOWN", "UNKNOWN");
            }
        }
        log.info("Notification DLQ audit actor={} operation={} action={} state={} examined={} broker={} sourceAck={}",
                actor, operation, action, result.outcome, result.examined, result.brokerAcceptance, result.sourceAck);
        metrics.counter("notification.dlq.operations", "action", action, "outcome", result.outcome).increment();
        return result;
    }

    private Result window(NotificationDlqBroker.Session session, UUID operation, Selection selected) throws Exception {
        Channel channel = session.channel;
        List<NotificationDlqMessage.Metadata> metadata = new ArrayList<>();
        int bytes = 0, examined = 0;
        // Hold all fetched messages unacked until connection close. Never fetch a
        // requeued poison message twice in the same command. At most window tags.
        for (int i = 0; i < config.getWindow(); i++) {
            session.check();
            GetResponse delivery = channel.basicGet(queue, false);
            if (delivery == null) break;
            examined++;
            bytes += delivery.getBody().length;
            if (bytes > config.getMaxWindowBytes()) return result(operation, "WINDOW_BYTE_LIMIT", metadata, examined, "NOT_ATTEMPTED", "NOT_SENT");
            var parsed = NotificationDlqMessage.parse(delivery, scope, sourceQueue, config.getMaxBodyBytes(), Instant.now());
            var item = parsed.metadata();
            metadata.add(item);
            if (selected != null && selected.eventId.equals(item.eventId()) && selected.fingerprint.equals(item.fingerprint())) {
                if (!item.replayable()) return result(operation, "INVALID_MESSAGE", metadata, examined, "NOT_ATTEMPTED", "NOT_SENT");
                session.check();
                AtomicBoolean returned = new AtomicBoolean();
                channel.addReturnListener(r -> returned.set(true));
                channel.confirmSelect();
                channel.basicPublish(exchange, routingKey, true, NotificationDlqMessage.replayProperties(delivery, parsed.event()), delivery.getBody());
                boolean confirmed;
                try { confirmed = channel.waitForConfirms(config.getRpcTimeoutMs()); }
                catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    return result(operation, "PUBLISH_AMBIGUOUS", metadata, examined, "UNKNOWN", "NOT_SENT");
                }
                if (returned.get()) return result(operation, "UNROUTABLE", metadata, examined, "RETURNED", "NOT_SENT");
                if (!confirmed) return result(operation, "PUBLISH_NACK", metadata, examined, "NACK", "NOT_SENT");
                try {
                    session.check();
                    channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
                    // Ordered RPC after ACK proves broker processed prior frames;
                    // disconnect before its reply still leaves an ambiguous result.
                    channel.queueDeclarePassive(queue);
                } catch (Exception e) { return result(operation, "SOURCE_ACK_AMBIGUOUS", metadata, examined, "CONFIRMED_ROUTED", "UNKNOWN"); }
                return result(operation, "REPLAYED", metadata, examined, "CONFIRMED_ROUTED", "ACK_PROCESSED");
            }
        }
        return result(operation, selected == null ? "INSPECTED_WINDOW" : "NOT_FOUND_IN_WINDOW", metadata, examined, "NOT_ATTEMPTED", "NOT_SENT");
    }
    private static Result result(UUID id, String outcome, List<NotificationDlqMessage.Metadata> messages, int count, String broker, String ack) {
        return new Result(id, outcome, List.copyOf(messages), count, broker, ack, "NOT_OBSERVED");
    }
}
