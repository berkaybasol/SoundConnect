package com.berkayb.soundconnect.shared.telemetry;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class BoundedOtlpMetricsTransportTest {
    @Test void redirectsAreRejectedAndCannotReachAnotherReceiver() throws Exception {
        try (var source = new MockWebServer(); var destination = new MockWebServer()) {
            source.start(); destination.start();
            source.enqueue(new MockResponse().setResponseCode(307).addHeader("Location", destination.url("/stolen")));
            try (var transport = new BoundedOtlpMetricsTransport(endpoint(source))) {
                transport.publish(new byte[]{1, 2, 3});
                assertThat(source.takeRequest(1, TimeUnit.SECONDS)).isNotNull();
                assertThat(destination.getRequestCount()).isZero();
                assertThat(transport.failedPublications()).isEqualTo(1);
                assertThat(transport.lastSuccessEpochSeconds()).isZero();
            }
        }
    }

    @Test void jvmDefaultProxyCannotInterceptThePrivateCollectorRequest() throws Exception {
        ProxySelector previous = ProxySelector.getDefault();
        var selected = new AtomicInteger();
        try (var source = new MockWebServer(); var maliciousProxy = new MockWebServer()) {
            source.start(); maliciousProxy.start();
            source.enqueue(new MockResponse().setResponseCode(200));
            ProxySelector.setDefault(new ProxySelector() {
                @Override public List<Proxy> select(URI uri) {
                    selected.incrementAndGet();
                    return List.of(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", maliciousProxy.getPort())));
                }
                @Override public void connectFailed(URI uri, SocketAddress address, IOException failure) { }
            });
            try (var transport = new BoundedOtlpMetricsTransport(endpoint(source))) {
                transport.publish(new byte[]{1});
                assertThat(source.takeRequest(1, TimeUnit.SECONDS)).isNotNull();
                assertThat(maliciousProxy.getRequestCount()).isZero();
                assertThat(selected).hasValue(0);
                assertThat(transport.lastSuccessEpochSeconds()).isPositive();
            }
        } finally { ProxySelector.setDefault(previous); }
    }

    @Test void oversizedPayloadIsRejectedBeforeAnyIo() throws Exception {
        try (var server = new MockWebServer()) {
            server.start();
            try (var transport = new BoundedOtlpMetricsTransport(endpoint(server))) {
                transport.publish(new byte[BoundedOtlpMetricsTransport.MAX_PAYLOAD_BYTES + 1]);
                assertThat(server.getRequestCount()).isZero();
                assertThat(transport.failedPublications()).isEqualTo(1);
            }
        }
    }

    @Test void oneInFlightRequestRejectsAdditionalExportsWithoutQueueing() throws Exception {
        try (var server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setResponseCode(200).setHeadersDelay(1, TimeUnit.SECONDS));
            try (var transport = new BoundedOtlpMetricsTransport(endpoint(server))) {
                var first = CompletableFuture.runAsync(() -> transport.publish(new byte[]{1}));
                assertThat(server.takeRequest(2, TimeUnit.SECONDS)).isNotNull();
                transport.publish(new byte[]{2});
                assertThat(transport.failedPublications()).isEqualTo(1);
                first.get(5, TimeUnit.SECONDS);
                assertThat(server.getRequestCount()).isEqualTo(1);
                assertThat(transport.lastSuccessEpochSeconds()).isPositive();
            }
        }
    }

    @Test void silentReceiverHitsDeadlineAndDoesNotThrowIntoApplicationCode() throws Exception {
        try (var server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            try (var transport = new BoundedOtlpMetricsTransport(endpoint(server))) {
                long started = System.nanoTime();
                transport.publish(new byte[]{1});
                assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started)).isLessThan(6);
                assertThat(transport.failedPublications()).isEqualTo(1);
            }
        }
    }

    private static String endpoint(MockWebServer server) { return "http://127.0.0.1:" + server.getPort() + "/v1/metrics"; }
}
