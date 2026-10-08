package com.berkayb.soundconnect.modules.notification.push.transport;

import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.LowLevelHttpRequest;
import com.google.api.client.http.LowLevelHttpResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Uses the same deadline for OAuth token acquisition and FCM. A late credential refresh can never
 * start another network request after the send deadline. No token/header/body logging is performed.
 */
final class DeadlineHttpTransport extends HttpTransport {
    private final HttpClient client;
    private final Duration readTimeout;
    private final ThreadLocal<Long> deadlineNanos = new ThreadLocal<>();

    DeadlineHttpTransport(HttpClient client, Duration readTimeout) {
        this.client = client;
        this.readTimeout = readTimeout;
    }

    void begin(Duration timeout) {
        deadlineNanos.set(System.nanoTime() + timeout.toNanos());
    }

    void end() {
        deadlineNanos.remove();
    }

    Duration remainingTimeout() throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Push request interrupted");
        }
        Long deadline = deadlineNanos.get();
        long remaining = deadline == null ? readTimeout.toNanos() : deadline - System.nanoTime();
        if (remaining <= 0) {
            throw new HttpTimeoutException("Push request deadline exceeded");
        }
        return Duration.ofNanos(Math.min(remaining, readTimeout.toNanos()));
    }

    HttpResponse<byte[]> send(HttpRequest.Builder request) throws IOException {
        try {
            return client.send(request.timeout(remainingTimeout()).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Push request interrupted");
        }
    }

    @Override
    public boolean supportsMethod(String method) {
        return true;
    }

    @Override
    protected LowLevelHttpRequest buildRequest(String method, String url) {
        return new LowLevelHttpRequest() {
            private final List<Map.Entry<String, String>> headers = new ArrayList<>();

            @Override
            public void addHeader(String name, String value) {
                headers.add(Map.entry(name, value));
            }

            @Override
            public LowLevelHttpResponse execute() throws IOException {
                remainingTimeout();
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                if (getStreamingContent() != null) {
                    getStreamingContent().writeTo(body);
                }
                HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                        .method(method, body.size() == 0 ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
                for (Map.Entry<String, String> header : headers) {
                    // JDK computes framing and Host; auth metadata endpoints need all other headers.
                    if (!List.of("content-length", "host", "connection", "expect", "upgrade")
                            .contains(header.getKey().toLowerCase(java.util.Locale.ROOT))) {
                        request.header(header.getKey(), header.getValue());
                    }
                }
                if (getContentType() != null) {
                    request.setHeader("Content-Type", getContentType());
                }
                if (getContentEncoding() != null) {
                    request.setHeader("Content-Encoding", getContentEncoding());
                }
                return new BufferedResponse(send(request));
            }
        };
    }

    private static final class BufferedResponse extends LowLevelHttpResponse {
        private final HttpResponse<byte[]> response;
        private final List<Map.Entry<String, String>> headers = new ArrayList<>();

        private BufferedResponse(HttpResponse<byte[]> response) {
            this.response = response;
            response.headers().map().forEach((name, values) ->
                    values.forEach(value -> headers.add(Map.entry(name, value))));
        }

        @Override public InputStream getContent() { return new ByteArrayInputStream(response.body()); }
        @Override public String getContentEncoding() { return first("content-encoding"); }
        @Override public long getContentLength() { return response.body().length; }
        @Override public String getContentType() { return first("content-type"); }
        @Override public String getStatusLine() { return "HTTP " + response.statusCode(); }
        @Override public int getStatusCode() { return response.statusCode(); }
        @Override public String getReasonPhrase() { return ""; }
        @Override public int getHeaderCount() { return headers.size(); }
        @Override public String getHeaderName(int index) { return headers.get(index).getKey(); }
        @Override public String getHeaderValue(int index) { return headers.get(index).getValue(); }
        private String first(String name) { return response.headers().firstValue(name).orElse(null); }
    }
}
