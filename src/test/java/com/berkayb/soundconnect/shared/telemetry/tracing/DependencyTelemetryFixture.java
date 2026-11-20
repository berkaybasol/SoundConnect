package com.berkayb.soundconnect.shared.telemetry.tracing;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import okhttp3.mockwebserver.MockWebServer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Actual private SDK/OTLP sink for provider-boundary tests in their owning packages. */
public final class DependencyTelemetryFixture implements AutoCloseable {
    private final MockWebServer collector;
    private final SafeTelemetryRuntime runtime;
    public final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    public final SafeDependencyTelemetry telemetry;

    public DependencyTelemetryFixture() throws Exception {
        collector = SafeDependencyTelemetryTest.collector();
        runtime = SafeDependencyTelemetryTest.runtime(collector, 1, "soundconnect-api");
        telemetry = new SafeDependencyTelemetry(runtime, meters);
    }

    public List<ExportTraceServiceRequest> exports() throws Exception {
        runtime.flush();
        List<ExportTraceServiceRequest> exports = new ArrayList<>();
        var request = collector.takeRequest(3, TimeUnit.SECONDS);
        while (request != null) {
            if (!"/v1/traces".equals(request.getPath())) throw new AssertionError("Unexpected provider telemetry signal");
            exports.add(ExportTraceServiceRequest.parseFrom(request.getBody().readByteArray()));
            request = collector.takeRequest(100, TimeUnit.MILLISECONDS);
        }
        return exports;
    }

    @Override public void close() throws Exception {
        runtime.close();
        collector.close();
        meters.close();
    }
}
