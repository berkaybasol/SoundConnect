package com.berkayb.soundconnect.modules.notification.push.transport;

import com.berkayb.soundconnect.shared.telemetry.tracing.DependencyTelemetryFixture;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.berkayb.soundconnect.modules.notification.push.transport.PushSendResult.Outcome.*;
import static org.assertj.core.api.Assertions.assertThat;

class FcmDependencyTelemetryTest {
    @Test void realProviderHttpOnExecutorExportsAcceptedOrErrorWithoutTokensPayloadOrProviderId() throws Exception {
        try (var fixture = new DependencyTelemetryFixture(); var provider = new MockWebServer();
             var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
             var executor = Executors.newSingleThreadExecutor()) {
            provider.start();
            provider.enqueue(new MockResponse().setResponseCode(200)
                    .setBody("{\"name\":\"projects/private-project/messages/private-message-id\"}"));
            provider.enqueue(new MockResponse().setResponseCode(503)
                    .setBody("{\"error\":{\"status\":\"UNAVAILABLE\",\"message\":\"private-response\"}}"));
            var properties = new FcmPushProperties();
            properties.setProjectId("private-project");
            var credentials = GoogleCredentials.create(new AccessToken("private-oauth-token", Date.from(Instant.now().plusSeconds(3600))));
            var transport = new FcmHttpV1Transport(credentials, new DeadlineHttpTransport(http, properties.getReadTimeout()),
                    new ObjectMapper(), executor, properties, Clock.systemUTC());
            transport.setDependencyTelemetry(fixture.telemetry);
            // Only the test replaces the fixed FCM target with an owned loopback fixture.
            ReflectionTestUtils.setField(transport, "endpoint", provider.url("/private-project/messages:send").uri());
            var envelope = new PushEnvelope("private-device-token", "private-title", "private-body",
                    Map.of("notificationId", "private-notification"), "private-collapse", Instant.now().plusSeconds(300));
            assertThat(transport.send(envelope).outcome()).isEqualTo(ACCEPTED);
            assertThat(transport.send(envelope).outcome()).isEqualTo(RETRYABLE_FAILURE);
            assertThat(provider.getRequestCount()).isEqualTo(2);
            assertThat(provider.takeRequest(1, TimeUnit.SECONDS).getHeader("Authorization")).isEqualTo("Bearer private-oauth-token");
            assertThat(fixture.meters.get("soundconnect.dependency.duration").tags("provider", "fcm", "operation", "send", "result", "accepted").timer().count()).isEqualTo(1);
            assertThat(fixture.meters.get("soundconnect.dependency.duration").tags("provider", "fcm", "operation", "send", "result", "error").timer().count()).isEqualTo(1);
            var exports = fixture.exports();
            assertThat(exports).isNotEmpty();
            assertThat(exports.toString()).contains("fcm send", "accepted", "error")
                    .doesNotContain("private-", "UNAVAILABLE", "Authorization", "exception", "http.url");
            assertThat(exports.stream().flatMap(export -> export.getResourceSpansList().stream())
                    .flatMap(resource -> resource.getScopeSpansList().stream()).flatMap(scope -> scope.getSpansList().stream()))
                    .allSatisfy(span -> assertThat(span.getParentSpanId().isEmpty()).isTrue());
        }
    }
}
