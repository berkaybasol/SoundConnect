package com.berkayb.soundconnect.modules.notification.dlq;

import com.rabbitmq.client.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class NotificationDlqMessageTest {
    static final Instant NOW = Instant.parse("2026-10-04T15:00:00Z");
    static final String BODY = "{\"eventId\":\"11111111-1111-1111-1111-111111111111\",\"recipientId\":\"22222222-2222-2222-2222-222222222222\",\"type\":\"SOCIAL_NEW_FOLLOWER\",\"title\":\"private-title\",\"message\":\"private-message\",\"payload\":{},\"occurredAt\":\"2026-10-01T00:00:00Z\"}";
    static GetResponse delivery(String body, Map<String, Object> headers) {
        return new GetResponse(new Envelope(7, false, "dlx", "notification.event"), new AMQP.BasicProperties.Builder()
                .contentType("application/json").headers(headers).timestamp(Date.from(NOW.minusSeconds(9999))).build(), body.getBytes(java.nio.charset.StandardCharsets.UTF_8), 0);
    }
    NotificationDlqMessage.Parsed parse(String body, Map<String, Object> headers) {
        return NotificationDlqMessage.parse(delivery(body, headers), "/\ndlq", "ingress", 65536, NOW);
    }
    @Test void fixedParserPreservesSnapshotAndDoesNotExposePrivateData() {
        var p = parse(BODY, Map.of());
        assertThat(p.metadata().replayable()).isTrue();
        assertThat(p.event().occurredAt()).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
        assertThat(p.metadata().toString()).doesNotContain("private", "22222222");
        assertThat(p.metadata().age().seconds()).isNull();
    }
    @Test void missingAndMalformedIdentitiesNeverRepaired() {
        for (String b : List.of(BODY.replace("11111111-1111-1111-1111-111111111111", ""), "{}", "[]", "not json", BODY + "{}"))
            assertThat(parse(b, Map.of()).metadata().replayable()).isFalse();
    }
    @Test void typeHeadersCannotLoadArbitraryClassesAndFutureSchemaRetained() {
        for (var h : List.of(Map.<String,Object>of("__TypeId__", "java.lang.Runtime"), Map.<String,Object>of("schemaVersion", 3), Map.<String,Object>of("eventId", UUID.randomUUID().toString())))
            assertThat(parse(BODY, h).metadata().replayable()).isFalse();
    }
    @Test void duplicateFieldsDeepJsonAndOversizedInputsRejected() {
        assertThat(parse(BODY.replace("\"payload\":{}", "\"payload\":{},\"payload\":{}"), Map.of()).metadata().replayable()).isFalse();
        assertThat(parse(BODY.replace("\"payload\":{}", "\"payload\":" + "[".repeat(25) + "0" + "]".repeat(25)), Map.of()).metadata().replayable()).isFalse();
        assertThat(parse("x".repeat(65537), Map.of()).metadata().reason()).isEqualTo("BODY_LIMIT");
    }
    @Test void fingerprintBindsExactBodyAndSource() {
        var a = parse(BODY, Map.of()).metadata().fingerprint();
        assertThat(parse(BODY + " ", Map.of()).metadata().fingerprint()).isNotEqualTo(a);
        assertThat(NotificationDlqMessage.parse(delivery(BODY, Map.of()), "other-vhost\ndlq", "ingress", 65536, NOW).metadata().fingerprint()).isNotEqualTo(a);
    }
    @Test void ageUsesMatchingFirstRejectedDeathOnly() {
        var h = Map.<String,Object>of("x-death", List.of(Map.of("queue", "other", "reason", "rejected", "time", Date.from(NOW.minusSeconds(999))),
                Map.of("queue", "ingress", "reason", "rejected", "time", Date.from(NOW.minusSeconds(25)), "count", 2L)));
        var age = parse(BODY, h).metadata().age();
        assertThat(age.seconds()).isEqualTo(25); assertThat(age.coverage()).isEqualTo("THIS_MESSAGE_ONLY");
        assertThat(age.reason()).isEqualTo("FIRST_DEATH_NOT_LATEST_ENTRY");
    }
    @Test void missingMalformedFutureOrNonRejectionAgesAreUnknown() {
        for (Object death : List.of("bad", Map.of("queue", "ingress", "reason", "rejected", "time", "bad"),
                Map.of("queue", "ingress", "reason", "expired", "time", Date.from(NOW.minusSeconds(8)), "count", 1L),
                Map.of("queue", "ingress", "reason", "rejected", "time", Date.from(NOW.plusSeconds(8)), "count", 1L)))
            assertThat(parse(BODY, Map.of("x-death", List.of(death))).metadata().age().seconds()).isNull();
    }
    @Test void replayDropsRoutingAndExpirationKeepsRawSafeHistory() {
        var d = delivery(BODY, Map.of("CC", List.of("secret-route"), "BCC", "other", "arbitrary", "private", "x-death", List.of()));
        var props = NotificationDlqMessage.replayProperties(d, parse(BODY, Map.of()).event());
        assertThat(props.getDeliveryMode()).isEqualTo(2);
        assertThat(props.getHeaders()).containsKeys("x-death", "schemaVersion").doesNotContainKeys("CC", "BCC", "arbitrary");
        assertThat(props.getExpiration()).isNull();
    }
}
