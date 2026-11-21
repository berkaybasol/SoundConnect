package com.berkayb.soundconnect.shared.telemetry;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** One in-flight export, no retry/export queue, no proxy/redirect, no response content retained. */
final class BoundedOtlpMetricsTransport implements AutoCloseable {
    static final int MAX_PAYLOAD_BYTES = 1_048_576;
    private final URI endpoint;
    private final ThreadPoolExecutor httpWorkers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(32), task -> {
                Thread thread = new Thread(task, "soundconnect-metrics-http");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private final HttpClient client;
    private final AtomicBoolean publishing = new AtomicBoolean();
    private final AtomicLong failures = new AtomicLong();
    private volatile boolean closed;
    private volatile long lastSuccess;

    BoundedOtlpMetricsTransport(String endpoint) {
        TelemetryMetricsProperties.validateEndpoint(endpoint);
        this.endpoint = URI.create(endpoint);
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).followRedirects(HttpClient.Redirect.NEVER)
                .executor(httpWorkers).proxy(new ProxySelector() {
                    @Override public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                    @Override public void connectFailed(URI uri, SocketAddress address, IOException failure) { }
                }).build();
    }

    void publish(byte[] payload) {
        if (closed || payload == null || payload.length == 0 || payload.length > MAX_PAYLOAD_BYTES
                || !publishing.compareAndSet(false, true)) {
            failures.incrementAndGet();
            return;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/x-protobuf")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build();
            // Return at headers, then cancel/close the body without reading or logging it.
            // Even a streaming/oversized error response cannot occupy or grow a body buffer.
            HttpResponse<java.io.InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var ignored = response.body()) {
                if (response.statusCode() >= 200 && response.statusCode() < 300) lastSuccess = Instant.now().getEpochSecond();
                else failures.incrementAndGet();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            failures.incrementAndGet();
        } catch (IOException | RuntimeException unavailable) {
            // Diagnostics are numeric; no receiver body, exception message, endpoint or secret is logged.
            failures.incrementAndGet();
        } finally {
            publishing.set(false);
        }
    }

    long failedPublications() { return failures.get(); }
    long lastSuccessEpochSeconds() { return lastSuccess; }

    @Override public void close() {
        closed = true;
        client.shutdownNow();
        httpWorkers.shutdownNow();
    }
}
