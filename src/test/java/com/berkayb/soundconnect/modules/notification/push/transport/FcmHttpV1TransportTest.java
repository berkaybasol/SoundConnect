package com.berkayb.soundconnect.modules.notification.push.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static com.berkayb.soundconnect.modules.notification.push.transport.PushSendResult.Outcome.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.*;

class FcmHttpV1TransportTest {
    private static final Instant NOW = Instant.parse("2026-09-22T12:00:00Z");
    private final ObjectMapper mapper = new ObjectMapper();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private HttpClient client;
    private ExecutorService executor;
    private GoogleCredentials credentials;
    private FcmPushProperties properties;
    private FcmHttpV1Transport transport;

    @BeforeEach
    void setUp() {
        client = mock(HttpClient.class);
        credentials = GoogleCredentials.create(new AccessToken("test-only-oauth", Date.from(NOW.plusSeconds(86400 * 365))));
        executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new SynchronousQueue<>(),
                Thread.ofPlatform().daemon().factory(), new ThreadPoolExecutor.AbortPolicy());
        properties = new FcmPushProperties();
        properties.setProjectId("soundconnect-test");
        transport = createTransport(credentials);
    }

    @AfterEach
    void tearDown() throws Exception {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
    }

    private FcmHttpV1Transport createTransport(GoogleCredentials source) {
        return new FcmHttpV1Transport(source, new DeadlineHttpTransport(client, properties.getReadTimeout()),
                mapper, executor, properties, clock);
    }

    private PushEnvelope envelope() {
        return new PushEnvelope("test-only-device-registration", "SoundConnect", "Yeni bildirimin var.",
                Map.of("notificationId", "notice-123"), "notice-123", NOW.plusSeconds(600));
    }

    @Test
    void acceptsProviderIdWithoutClaimingDisplayAndSubmitsOnce() throws Exception {
        respond(200, "{\"name\":\"projects/soundconnect-test/messages/0:123%abc\"}", Map.of());
        PushSendResult result = transport.send(envelope());
        assertThat(result.outcome()).isEqualTo(ACCEPTED);
        assertThat(result.providerMessageId()).isEqualTo("projects/soundconnect-test/messages/0:123%abc");
        verify(client, times(1)).send(argThat(request -> request.method().equals("POST")
                && request.uri().equals(URI.create("https://fcm.googleapis.com/v1/projects/soundconnect-test/messages:send"))
                && request.timeout().orElseThrow().compareTo(properties.getReadTimeout()) <= 0),
                ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any());
    }

    @Test
    void payloadHasVisibleNotificationsMatchingCollapseAndExpiry() throws Exception {
        var json = FcmWireAssertions.send(transport, client, envelope());
        assertThat(json.at("/message/android/priority").asText()).isEqualTo("HIGH");
        assertThat(json.at("/message/android/notification/channel_id").asText()).isEqualTo("soundconnect_notifications");
        assertThat(json.at("/message/android/notification/tag").asText()).isEqualTo("notice-123");
        assertThat(json.at("/message/android/collapse_key").asText()).isEqualTo("soundconnect_updates");
        assertThat(json.at("/message/android/ttl").asText()).isEqualTo("600s");
        assertThat(json.at("/message/apns/headers/apns-push-type").asText()).isEqualTo("alert");
        assertThat(json.at("/message/apns/headers/apns-priority").asText()).isEqualTo("10");
        assertThat(json.at("/message/apns/headers/apns-collapse-id").asText()).isEqualTo("notice-123");
        assertThat(json.at("/message/apns/headers/apns-expiration").asText()).isEqualTo(Long.toString(NOW.plusSeconds(600).getEpochSecond()));
        assertThat(json.at("/message/notification/body").asText()).isEqualTo(envelope().body());
        var other = envelope();
        var otherJson = FcmWireAssertions.send(transport, client, new PushEnvelope(other.token(), other.title(), other.body(),
                Map.of("notificationId", "notice-456"), "notice-456", other.expiresAt()));
        assertThat(otherJson.at("/message/android/collapse_key").asText()).isEqualTo("soundconnect_updates");
        assertThat(otherJson.at("/message/android/notification/tag").asText()).isEqualTo("notice-456");
        assertThat(otherJson.at("/message/apns/headers/apns-collapse-id").asText()).isEqualTo("notice-456");
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"ANDROID_DM_V1"})
    void capableAndroidUsesDataOnlyWithoutOsAutoDisplay(String presentation) throws Exception {
        var data=Map.of("notificationId","notice-123","type","DM_NEW_MESSAGE",
                "presentationVersion",presentation,"senderName","Deniz","senderAvatarUrl","https://example.cloudfront.net/a.png");
        var json=FcmWireAssertions.sendNative(transport,client,new PushEnvelope(envelope().token(),"SoundConnect",
                "Unused generic body",data,"notice-123",NOW.plusSeconds(600)),"600s");
        assertThat(json.at("/message/notification").isMissingNode()).isTrue();
        assertThat(json.at("/message/android/notification").isMissingNode()).isTrue();
        assertThat(json.at("/message/apns").isMissingNode()).isTrue();
        assertThat(json.at("/message/data/senderName").asText()).isEqualTo("Deniz");
        assertThat(json.at("/message/android/priority").asText()).isEqualTo("HIGH");
        assertThat(json.toString()).doesNotContain("Unused generic body");
    }

    @Test
    void sameRecipientEventsSubmitDistinctSameAndCrossFamilyRequestsWithFixedExpiry() throws Exception {
        String recipient = "00000000-0000-0000-0000-000000000100";
        var ids = List.of("00000000-0000-0000-0000-000000000001",
                "00000000-0000-0000-0000-000000000002", "00000000-0000-0000-0000-000000000003");
        var wires = new java.util.ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        for (int i = 0; i < ids.size(); i++) {
            var data = new java.util.HashMap<>(Map.of("notificationId", ids.get(i), "recipientId", recipient,
                    "type", i < 2 ? "DM_NEW_MESSAGE" : "EVENT_PERFORMER_APPROVED",
                    "presentationVersion", i < 2 ? "ANDROID_DM_V1" : "ANDROID_VENUE_V1",
                    "sentAt", Long.toString(NOW.toEpochMilli()),
                    "expiresAt", Long.toString(NOW.plusSeconds(600).toEpochMilli())));
            if (i < 2) {
                data.put("conversationId", "00000000-0000-0000-0000-000000000200");
                data.put("senderName", "Fixture sender");
            } else {
                data.put("displayVariant", "DEFAULT");
            }
            var event = new PushEnvelope(envelope().token(), "Private title", "Private body", data, ids.get(i), NOW.plusSeconds(600));
            wires.add(FcmWireAssertions.sendNative(transport, client, event, "600s"));
            if (i == 2) {
                var later = new FcmHttpV1Transport(credentials, new DeadlineHttpTransport(client, properties.getReadTimeout()),
                        mapper, executor, properties, Clock.fixed(NOW.plusSeconds(120), ZoneOffset.UTC));
                var retry = FcmWireAssertions.sendNative(later, client, event, "480s");
                assertThat(retry.at("/message/data")).isEqualTo(wires.get(i).at("/message/data"));
                var expired = new FcmHttpV1Transport(credentials, new DeadlineHttpTransport(client, properties.getReadTimeout()),
                        mapper, executor, properties, Clock.fixed(event.expiresAt(), ZoneOffset.UTC));
                assertThat(expired.send(event).errorCode()).isEqualTo("EXPIRED");
            }
        }
        assertThat(wires).extracting(wire -> wire.at("/message/data/notificationId").asText()).containsExactlyElementsOf(ids);
        assertThat(wires).allSatisfy(wire -> assertThat(wire.toString()).doesNotContain("Private"));
        verify(client, times(4)).send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any());
    }

    @ParameterizedTest
    @CsvSource({
            "404,UNREGISTERED,INVALID_DEVICE",
            "400,INVALID_ARGUMENT,PERMANENT_FAILURE",
            "403,SENDER_ID_MISMATCH,PERMANENT_FAILURE",
            "401,THIRD_PARTY_AUTH_ERROR,PERMANENT_FAILURE",
            "429,QUOTA_EXCEEDED,RETRYABLE_FAILURE",
            "500,INTERNAL,RETRYABLE_FAILURE",
            "503,UNAVAILABLE,RETRYABLE_FAILURE"
    })
    void classifiesStructuredFcmErrorsWithoutEchoingProviderMessage(int status, String code,
                                                                  PushSendResult.Outcome outcome) throws Exception {
        respond(status, "{\"error\":{\"message\":\"sensitive echoed token\",\"details\":[{\"@type\":"
                + "\"type.googleapis.com/google.firebase.fcm.v1.FcmError\",\"errorCode\":\"" + code + "\"}]}}", Map.of());
        PushSendResult result = transport.send(envelope());
        assertThat(result.outcome()).isEqualTo(outcome);
        assertThat(result.errorCode()).isEqualTo(code);
        assertThat(result.toString()).doesNotContain("sensitive", envelope().token());
        verify(client, times(1)).send(any(), ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any());
    }

    @ParameterizedTest
    @CsvSource({"120,120", "2,60"})
    void respectsRetryAfterAndMinimumQuotaDelay(String retryAfter, long expectedSeconds) throws Exception {
        respond(429, "{}", Map.of("Retry-After", List.of(retryAfter)));
        assertThat(transport.send(envelope()).retryAfter()).isEqualTo(Duration.ofSeconds(expectedSeconds));
        verify(client, times(1)).send(any(), ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any());
    }

    @Test
    void parsesHttpDateRetryAfter() throws Exception {
        respond(503, "not-json", Map.of("Retry-After", List.of("Tue, 22 Sep 2026 12:03:00 GMT")));
        assertThat(transport.send(envelope()).retryAfter()).isEqualTo(Duration.ofMinutes(3));
    }

    @Test
    void redirectsAndUnknownErrorsDoNotLeakOrInvalidateDevice() throws Exception {
        respond(302, "{\"error\":{\"status\":\"private-device-token\"}}", Map.of());
        var result = transport.send(envelope());
        assertThat(result.outcome()).isEqualTo(PERMANENT_FAILURE);
        assertThat(result.errorCode()).isEqualTo("HTTP_302");
    }

    @ParameterizedTest(name = "{0} never leaves the process")
    @ValueSource(strings = {"EXPIRED", "PAYLOAD_TOO_LARGE", "INVALID_ENVELOPE"})
    void expiredOversizedAndInvalidDataNeverLeaveProcess(String expectedCode) {
        var sample = envelope();
        // Each invocation gets a fresh executor: Future completion does not guarantee its worker is idle.
        var invalid = switch (expectedCode) {
            case "EXPIRED" -> new PushEnvelope(sample.token(), sample.title(), sample.body(), sample.data(),
                    sample.collapseKey(), NOW);
            case "PAYLOAD_TOO_LARGE" -> new PushEnvelope(sample.token(), sample.title(), "x".repeat(5000), sample.data(),
                    sample.collapseKey(), sample.expiresAt());
            case "INVALID_ENVELOPE" -> new PushEnvelope(sample.token(), sample.title(), sample.body(),
                    Map.of("google.private", "value"), sample.collapseKey(), sample.expiresAt());
            default -> throw new IllegalArgumentException("Unknown validation case: " + expectedCode);
        };
        assertThat(transport.send(invalid).errorCode()).isEqualTo(expectedCode);
        verifyNoInteractions(client);
    }

    @Test
    void envelopeToStringRedactsPrivateContent() {
        assertThat(envelope().toString()).isEqualTo("PushEnvelope[redacted]");
    }

    @Test
    void noWaitingQueueWhenCapacityIsBusy() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        executor.submit(() -> { started.countDown(); release.await(); return null; });
        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        try {
            assertThat(transport.send(envelope()).errorCode()).isEqualTo("LOCAL_CAPACITY");
            verifyNoInteractions(client);
        } finally {
            release.countDown();
        }
    }

    @Test
    void lateCredentialCompletionCannotSubmitPushAfterTotalDeadline() throws Exception {
        properties.setTotalTimeout(Duration.ofMillis(100));
        GoogleCredentials blockedCredentials = mock(GoogleCredentials.class);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        when(blockedCredentials.getRequestMetadata(any(URI.class))).thenAnswer(invocation -> {
            // Even a credential source that ignores cancellation cannot cause a late FCM submission.
            while (release.getCount() > 0) {
                try { release.await(); } catch (InterruptedException ignored) { }
            }
            completed.countDown();
            return Map.of("Authorization", List.of("Bearer test-only"));
        });
        transport = createTransport(blockedCredentials);
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                    assertThat(transport.send(envelope()).errorCode()).isEqualTo("SEND_TIMEOUT"));
        } finally {
            release.countDown();
        }
        assertThat(completed.await(1, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();
        assertThat(executor.awaitTermination(1, TimeUnit.SECONDS)).isTrue();
        verifyNoInteractions(client);
    }

    @SuppressWarnings("unchecked")
    private void respond(int status, String body, Map<String, List<String>> headers) throws Exception {
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        when(response.headers()).thenReturn(HttpHeaders.of(headers, (name, value) -> true));
        when(client.send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
                .thenReturn(response);
    }
}
