package com.berkayb.soundconnect.modules.notification.push.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockito.ArgumentCaptor;

import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Inspects the bytes submitted by the real transport; does not simulate FCM delivery. */
final class FcmWireAssertions {
    private static final ObjectMapper JSON = new ObjectMapper();

    private FcmWireAssertions() { }

    @SuppressWarnings("unchecked")
    static JsonNode send(FcmHttpV1Transport transport, HttpClient client, PushEnvelope envelope) throws Exception {
        int previous = mockingDetails(client).getInvocations().size();
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"name\":\"projects/soundconnect-test/messages/fixture\"}".getBytes(StandardCharsets.UTF_8));
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of(), (name, value) -> true));
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        PushSendResult result = transport.send(envelope);
        assertThat(result.outcome()).as("wire send errorCode=%s", result.errorCode())
                .isEqualTo(PushSendResult.Outcome.ACCEPTED);
        var requests = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client, times(previous + 1)).send(requests.capture(), any(HttpResponse.BodyHandler.class));
        verifyNoMoreInteractions(client);
        HttpRequest request = requests.getValue();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.uri().toString()).isEqualTo("https://fcm.googleapis.com/v1/projects/soundconnect-test/messages:send");
        var bytes = HttpResponse.BodySubscribers.ofByteArray();
        request.bodyPublisher().orElseThrow().subscribe(new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>() {
            public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) { bytes.onSubscribe(subscription); }
            public void onNext(java.nio.ByteBuffer buffer) { bytes.onNext(java.util.List.of(buffer)); }
            public void onError(Throwable error) { bytes.onError(error); }
            public void onComplete() { bytes.onComplete(); }
        });
        return JSON.readTree(bytes.getBody().toCompletableFuture().get(2, TimeUnit.SECONDS));
    }

    static JsonNode sendNative(FcmHttpV1Transport transport, HttpClient client, PushEnvelope envelope,
                               String remainingTtl) throws Exception {
        JsonNode wire = send(transport, client, envelope);
        assertThat(wire.at("/message/data")).isEqualTo(JSON.valueToTree(envelope.data()));
        assertThat(wire.at("/message/token").asText()).isEqualTo(envelope.token());
        assertThat(wire.at("/message/android/priority").asText()).isEqualTo("HIGH");
        assertThat(wire.at("/message/android/ttl").asText()).isEqualTo(remainingTtl);
        for (String path : new String[]{"/message/android/collapse_key", "/message/notification",
                "/message/android/notification", "/message/apns"}) {
            assertThat(wire.at(path).isMissingNode()).as("native wire omits %s", path).isTrue();
        }
        return wire;
    }
}
