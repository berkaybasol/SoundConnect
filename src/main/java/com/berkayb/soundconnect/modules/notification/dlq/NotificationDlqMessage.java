package com.berkayb.soundconnect.modules.notification.dlq;

import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationInboundEvent;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rabbitmq.client.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** Fixed JSON target only; AMQP class/type names are never passed to a class loader. */
final class NotificationDlqMessage {
    private static final ObjectMapper JSON = JsonMapper.builder(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(20).maxStringLength(65536).maxNumberLength(64).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .addModule(new JavaTimeModule()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    static final String TYPE = NotificationInboundEvent.class.getName();
    record Age(Long seconds, String source, String coverage, String reason, Instant measuredAt) { }
    record Metadata(UUID eventId, String fingerprint, String type, boolean replayable, String reason, int bytes, Age age) { }
    record Parsed(Metadata metadata, NotificationInboundEvent event) { }

    static Parsed parse(GetResponse delivery, String scope, String sourceQueue, int maxBytes, Instant now) {
        byte[] body = delivery.getBody();
        var protocol = delivery.getProps();
        var protocolHeaders = protocol.getHeaders() == null ? Map.<String, Object>of() : protocol.getHeaders();
        String fingerprint = hash(scope + "\n" + protocol.getContentType() + "\n" + protocol.getContentEncoding()
                + "\n" + protocolHeaders.get("schemaVersion") + "\n" + protocolHeaders.get("__TypeId__") + "\n", body);
        Age age = age(delivery.getProps().getHeaders(), sourceQueue, now);
        NotificationInboundEvent event = null;
        String reason = "VALID";
        try {
            if (body.length > maxBytes) throw new Invalid("BODY_LIMIT");
            var p = delivery.getProps();
            if (!"application/json".equals(p.getContentType()) || (p.getContentEncoding() != null && !"UTF-8".equalsIgnoreCase(p.getContentEncoding())))
                throw new Invalid("UNSUPPORTED_ENCODING");
            Map<String, Object> h = p.getHeaders() == null ? Map.of() : p.getHeaders();
            validateHistory(h);
            if (h.containsKey("__TypeId__") && !TYPE.equals(h.get("__TypeId__").toString())) throw new Invalid("UNSUPPORTED_TYPE");
            if (h.containsKey("schemaVersion") && !Integer.valueOf(2).equals(h.get("schemaVersion"))) throw new Invalid("UNSUPPORTED_SCHEMA");
            JsonNode tree = JSON.readTree(body);
            if (tree == null || !tree.isObject()) throw new Invalid("MALFORMED_JSON");
            var fields = tree.fieldNames();
            var allowed = Set.of("eventId", "recipientId", "type", "title", "message", "payload", "emailForce", "occurredAt");
            while (fields.hasNext()) if (!allowed.contains(fields.next())) throw new Invalid("UNSUPPORTED_SCHEMA");
            for (String key : List.of("eventId", "recipientId")) {
                String id = tree.path(key).asText("");
                if (!UUID.fromString(id).toString().equals(id)) throw new Invalid("MALFORMED_IDENTITY");
            }
            event = JSON.treeToValue(tree, NotificationInboundEvent.class);
            if (event.type() == null || event.occurredAt() == null) throw new Invalid("MISSING_SNAPSHOT");
            if (event.title() != null && event.title().length() > 160 || event.message() != null && event.message().length() > 1000)
                throw new Invalid("CONTENT_LIMIT");
            if (h.containsKey("eventId") && !event.eventId().toString().equals(h.get("eventId").toString())) throw new Invalid("HEADER_ID_MISMATCH");
            if (h.containsKey("eventType") && !"NOTIFICATION_INBOUND".equals(h.get("eventType").toString())) throw new Invalid("UNSUPPORTED_TYPE");
        } catch (Invalid e) { reason = e.code; }
        catch (Exception e) { reason = "MALFORMED_JSON_OR_IDENTITY"; }
        // No body, recipient, title or exception detail can leave this boundary.
        return new Parsed(new Metadata(event == null ? null : event.eventId(), fingerprint,
                event == null || event.type() == null ? null : event.type().name(), reason.equals("VALID"), reason, body.length, age), event);
    }

    private static void validateHistory(Map<String, Object> headers) {
        if (headers.containsKey("__ContentTypeId__") || headers.containsKey("__KeyTypeId__")) throw new Invalid("UNSUPPORTED_TYPE");
        if (headers.containsKey("x-death")) {
            if (!(headers.get("x-death") instanceof List<?> entries) || entries.size() > 32) throw new Invalid("MALFORMED_HISTORY");
            for (Object entry : entries) {
                if (!(entry instanceof Map<?, ?> m) || m.size() > 8) throw new Invalid("MALFORMED_HISTORY");
                for (var e : m.entrySet()) {
                    String key = String.valueOf(e.getKey()); Object v = e.getValue();
                    boolean valid = switch (key) {
                        case "queue", "exchange", "reason", "original-expiration" -> v != null && v.toString().length() <= 255;
                        case "time" -> v instanceof Date;
                        case "count" -> v instanceof Long || v instanceof Integer;
                        case "routing-keys" -> v instanceof List<?> routes && routes.size() <= 16
                                && routes.stream().allMatch(r -> r != null && r.toString().length() <= 255);
                        default -> false;
                    };
                    if (!valid) throw new Invalid("MALFORMED_HISTORY");
                }
            }
        }
        for (String k : List.of("x-first-death-queue", "x-first-death-reason", "x-first-death-exchange",
                "x-last-death-queue", "x-last-death-reason", "x-last-death-exchange"))
            if (headers.containsKey(k) && (headers.get(k) == null || headers.get(k).toString().length() > 255)) throw new Invalid("MALFORMED_HISTORY");
    }

    static Age age(Map<String, Object> headers, String sourceQueue, Instant now) {
        if (headers != null && headers.get("x-death") instanceof List<?> deaths && deaths.size() <= 32) {
            for (Object item : deaths) {
                if (item instanceof Map<?, ?> death && sourceQueue.equals(String.valueOf(death.get("queue")))
                        && "rejected".equals(String.valueOf(death.get("reason")))) {
                    if (death.get("time") instanceof Date time && death.get("count") instanceof Number count && count.longValue() > 0
                            && !time.toInstant().isAfter(now))
                        return new Age(Duration.between(time.toInstant(), now).getSeconds(), "MATCHING_X_DEATH_FIRST", "THIS_MESSAGE_ONLY", "FIRST_DEATH_NOT_LATEST_ENTRY", now);
                    return new Age(null, "UNKNOWN", "THIS_MESSAGE_ONLY", "INVALID_OR_FUTURE_X_DEATH", now);
                }
            }
        }
        return new Age(null, "UNKNOWN", "THIS_MESSAGE_ONLY", "NO_MATCHING_X_DEATH", now);
    }

    static AMQP.BasicProperties replayProperties(GetResponse delivery, NotificationInboundEvent event) {
        Map<String, Object> safe = new HashMap<>();
        // Retain broker history only; never forward CC/BCC, expiration or arbitrary routing/type headers.
        var h = delivery.getProps().getHeaders();
        if (h != null) for (String k : List.of("x-death", "x-first-death-queue", "x-first-death-reason", "x-first-death-exchange",
                "x-last-death-queue", "x-last-death-reason", "x-last-death-exchange")) if (h.containsKey(k)) safe.put(k, h.get(k));
        safe.put("__TypeId__", TYPE); safe.put("eventId", event.eventId().toString());
        safe.put("schemaVersion", 2); safe.put("eventType", "NOTIFICATION_INBOUND");
        return new AMQP.BasicProperties.Builder().contentType("application/json").contentEncoding("UTF-8")
                .deliveryMode(2).correlationId(event.eventId().toString()).timestamp(delivery.getProps().getTimestamp()).headers(safe).build();
    }
    static String hash(String prefix, byte[] data) {
        try { var d = MessageDigest.getInstance("SHA-256"); d.update(prefix.getBytes(StandardCharsets.UTF_8)); return HexFormat.of().formatHex(d.digest(data)); }
        catch (Exception e) { throw new IllegalStateException("SHA256 unavailable"); }
    }
    private static final class Invalid extends RuntimeException { final String code; Invalid(String code) { this.code = code; } }
}
