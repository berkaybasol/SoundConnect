package com.berkayb.soundconnect.shared.telemetry.tracing;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.concurrent.TimeUnit;

import static com.berkayb.soundconnect.shared.telemetry.tracing.SafeDependencyTelemetry.Operation.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SafeDependencyTelemetryTest {
    @Test void disabledNonWebConfigurationCreatesNoSdkOrFacade() {
        new ApplicationContextRunner().withUserConfiguration(SafeTelemetryConfiguration.class)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(SafeTelemetryRuntime.class);
                    assertThat(context).doesNotHaveBean(SafeDependencyTelemetry.class);
                });
    }

    @Test void dependencyOnlyConfigurationRequiresValidIdentityDestinationAndSampling() {
        for (String override : new String[]{"app.telemetry.metrics.service-name=private-user-name",
                "app.observability.http-telemetry.sample-probability=NaN",
                "app.observability.http-telemetry.collector-endpoint=https://example.org:443"}) {
            new ApplicationContextRunner().withUserConfiguration(SafeTelemetryConfiguration.class)
                    .withBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new)
                    .withPropertyValues("app.observability.dependency-telemetry.enabled=true",
                            "app.telemetry.metrics.service-name=soundconnect-media-worker", override)
                    .run(context -> assertThat(context).hasFailed());
        }
    }

    @Test void workerNonWebUsesOnePrivateRuntimeAndDoesNotReadActiveOrIncomingContext() throws Exception {
        var meters = new SimpleMeterRegistry();
        try (var collector = collector();
             var runtime = runtime(collector, 1, "soundconnect-media-worker")) {
            var telemetry = new SafeDependencyTelemetry(runtime, meters);
            var foreign = Span.wrap(SpanContext.createFromRemoteParent("11223344556677889900aabbccddeeff",
                    "1122334455667788", TraceFlags.getSampled(), TraceState.builder().put("secret", "private-header").build()));
            try (var ignored = Context.root().with(foreign).makeCurrent()) {
                telemetry.observe(S3_HEAD_OBJECT, () -> "secret-provider-return");
                assertThatThrownBy(() -> telemetry.observe(S3_DELETE_OBJECT, () -> {
                    throw new IllegalStateException("secret-key secret-body private@example.invalid");
                })).isInstanceOf(IllegalStateException.class);
            }
            runtime.flush();
            var request = collector.takeRequest(3, TimeUnit.SECONDS);
            assertThat(request).isNotNull();
            assertThat(request.getPath()).isEqualTo("/v1/traces");
            assertThat(request.getHeader("Authorization")).isNull();
            var exported = ExportTraceServiceRequest.parseFrom(request.getBody().readByteArray());
            assertThat(exported.getResourceSpansList()).hasSize(1);
            var resource = exported.getResourceSpans(0);
            assertThat(resource.getResource().getAttributesList()).hasSize(1);
            assertThat(resource.getResource().getAttributes(0).getValue().getStringValue()).isEqualTo("soundconnect-media-worker");
            assertThat(resource.getSchemaUrl()).isEmpty();
            var scope = resource.getScopeSpans(0);
            assertThat(scope.getScope().getName()).isEqualTo("soundconnect.safe-dependency");
            assertThat(scope.getScope().getAttributesList()).isEmpty();
            assertThat(scope.getScope().getVersion()).isEmpty();
            assertThat(scope.getSchemaUrl()).isEmpty();
            assertThat(scope.getSpansList()).hasSize(2).allSatisfy(span -> {
                assertThat(span.getParentSpanId().isEmpty()).isTrue();
                assertThat(java.util.HexFormat.of().formatHex(span.getTraceId().toByteArray()))
                        .isNotEqualTo("11223344556677889900aabbccddeeff");
                assertThat(span.getKind()).isEqualTo(io.opentelemetry.proto.trace.v1.Span.SpanKind.SPAN_KIND_CLIENT);
                assertThat(span.getAttributesList().stream().map(attribute -> attribute.getKey()).toList())
                        .containsExactlyInAnyOrder("dependency.provider", "dependency.operation", "dependency.result");
                assertThat(span.getEventsList()).isEmpty();
                assertThat(span.getLinksList()).isEmpty();
                assertThat(span.getTraceState()).isEmpty();
                assertThat(span.getStatus().getMessage()).isEmpty();
            });
            assertThat(exported.toString()).doesNotContain("secret", "private", "exception", "http.url", "host.name");
            assertThat(meters.get("soundconnect.dependency.duration").tags("provider", "s3", "operation", "head_object", "result", "success").timer().count()).isEqualTo(1);
            assertThat(meters.get("soundconnect.dependency.duration").tags("provider", "s3", "operation", "delete_object", "result", "error").timer().count()).isEqualTo(1);
            assertThat(collector.takeRequest(150, TimeUnit.MILLISECONDS)).isNull(); // no provider error logs
        } finally { meters.close(); }
    }

    @Test void samplingZeroStillCountsEveryProviderCallAndRepeatedCloseCannotDoubleCount() throws Exception {
        var meters = new SimpleMeterRegistry();
        try (var collector = collector();
             var runtime = runtime(collector, 0, "soundconnect-api")) {
            var telemetry = new SafeDependencyTelemetry(runtime, meters);
            for (var operation : SafeDependencyTelemetry.Operation.values()) {
                var call = telemetry.start(operation);
                call.succeeded(); call.close(); call.close();
                try (var failure = telemetry.start(operation)) { }
            }
            assertThat(meters.getMeters()).hasSize(26); // 13 bounded operations, success/error each.
            assertThat(meters.get("soundconnect.dependency.duration").tags("provider", "s3", "operation", "list_versions", "result", "success").timer().count()).isEqualTo(1);
            assertThat(meters.getMeters()).allSatisfy(meter -> assertThat(((io.micrometer.core.instrument.Timer) meter).count()).isEqualTo(1));
            assertThat(meters.get("soundconnect.dependency.duration").tags("provider", "mailersend", "result", "accepted").timer().count()).isEqualTo(1);
            runtime.flush();
            assertThat(collector.takeRequest(150, TimeUnit.MILLISECONDS)).isNull();
        } finally { meters.close(); }
    }

    @Test void dependencyOnlyWorkerSpringConfigurationHasNoHttpObservationAdapter() throws Exception {
        try (var collector = collector()) {
            new ApplicationContextRunner().withUserConfiguration(SafeTelemetryConfiguration.class)
                    .withBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new)
                    .withPropertyValues("app.observability.dependency-telemetry.enabled=true",
                            "app.telemetry.metrics.service-name=soundconnect-media-worker",
                            "app.observability.http-telemetry.collector-endpoint=http://127.0.0.1:" + collector.getPort())
                    .run(context -> {
                        assertThat(context).hasSingleBean(SafeTelemetryRuntime.class).hasSingleBean(SafeDependencyTelemetry.class);
                        assertThat(context).doesNotHaveBean(SafeHttpObservationHandler.class);
                    });
        }
    }

    static SafeTelemetryRuntime runtime(MockWebServer collector, double sample, String service) {
        var properties = new SafeHttpTelemetryProperties();
        properties.setCollectorEndpoint("http://127.0.0.1:" + collector.getPort());
        properties.setSampleProbability(sample);
        return SafeTelemetryRuntime.create(properties, service);
    }

    static MockWebServer collector() throws Exception {
        var server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) { return new MockResponse().setResponseCode(200); }
        });
        server.start();
        return server;
    }
}
