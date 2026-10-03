package com.berkayb.soundconnect.modules.notification.push.transport;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;

import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class DeadlineHttpTransportTest {
    @Test
    void expiredDeadlineRejectsNetworkBeforeSending() {
        HttpClient client = mock(HttpClient.class);
        DeadlineHttpTransport http = new DeadlineHttpTransport(client, Duration.ofSeconds(5));
        http.begin(Duration.ZERO);
        try {
            assertThatThrownBy(() -> http.send(HttpRequest.newBuilder(URI.create("https://example.invalid"))))
                    .isInstanceOf(HttpTimeoutException.class);
            verifyNoInteractions(client);
        } finally {
            http.end();
        }
    }

    @Test
    void interruptedSendCannotBeginFurtherNetworkAttempt() {
        HttpClient client = mock(HttpClient.class);
        DeadlineHttpTransport http = new DeadlineHttpTransport(client, Duration.ofSeconds(5));
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(http::remainingTimeout).isInstanceOf(InterruptedIOException.class);
            verifyNoInteractions(client);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void credentialHttpUsesSameDeadlineAndReturnsProviderHeaders() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{}".getBytes(StandardCharsets.UTF_8));
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of("content-type", List.of("application/json")), (a, b) -> true));
        when(client.send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any())).thenReturn(response);
        DeadlineHttpTransport http = new DeadlineHttpTransport(client, Duration.ofSeconds(5));
        http.begin(Duration.ofSeconds(1));
        try {
            var request = http.createRequestFactory().buildGetRequest(
                    new com.google.api.client.http.GenericUrl("https://oauth2.googleapis.com/test-only"));
            request.setLoggingEnabled(false);
            var result = request.execute();
            assertThat(result.getStatusCode()).isEqualTo(200);
            assertThat(result.getContentType()).isEqualTo("application/json");
            result.disconnect();
            ArgumentCaptor<HttpRequest> capture = ArgumentCaptor.forClass(HttpRequest.class);
            verify(client).send(capture.capture(), ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any());
            assertThat(capture.getValue().timeout().orElseThrow()).isLessThanOrEqualTo(Duration.ofSeconds(1));
        } finally {
            http.end();
        }
    }
}
