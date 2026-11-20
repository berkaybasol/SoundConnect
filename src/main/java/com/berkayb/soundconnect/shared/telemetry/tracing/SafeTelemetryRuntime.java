package com.berkayb.soundconnect.shared.telemetry.tracing;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.logs.LogLimits;
import io.opentelemetry.sdk.common.export.ProxyOptions;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanLimits;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;

import java.time.Duration;
import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;


/** One bounded, private SDK shared by the explicitly instrumented HTTP and provider boundaries. */
final class SafeTelemetryRuntime implements AutoCloseable {
    static final int MAX_QUEUE = 256;
    static final int MAX_BATCH = 32;
    static final Duration EXPORT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration EXPORT_DELAY = Duration.ofSeconds(5);
    private static final ProxyOptions NO_PROXY = ProxyOptions.create(new ProxySelector() {
        @Override public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
        @Override public void connectFailed(URI uri, SocketAddress address, IOException error) { }
    });
    private final SdkTracerProvider traces;
    private final SdkLoggerProvider logs;

    private SafeTelemetryRuntime(SdkTracerProvider traces, SdkLoggerProvider logs) {
        this.traces = traces;
        this.logs = logs;
    }

    static SafeTelemetryRuntime create(SafeHttpTelemetryProperties properties, String serviceName) {
        String endpoint = properties.validatedEndpoint();
        if (!Set.of("soundconnect-api", "soundconnect-media-worker").contains(serviceName == null ? "" : serviceName)) {
            throw new IllegalArgumentException("Telemetry requires an explicit API or media-worker service identity");
        }
        // Explicit resource bypasses environment, hostname, process and detector metadata.
        Resource resource = Resource.create(Attributes.of(AttributeKey.stringKey("service.name"), serviceName));
        var spanExporter = OtlpHttpSpanExporter.builder().setEndpoint(endpoint + "/v1/traces")
                .setProxy(NO_PROXY).setRetryPolicy(null).setConnectTimeout(EXPORT_TIMEOUT).setTimeout(EXPORT_TIMEOUT).build();
        var logExporter = OtlpHttpLogRecordExporter.builder().setEndpoint(endpoint + "/v1/logs")
                .setProxyOptions(NO_PROXY).setRetryPolicy(null).setConnectTimeout(EXPORT_TIMEOUT).setTimeout(EXPORT_TIMEOUT).build();
        var traces = SdkTracerProvider.builder().setResource(resource)
                .setSampler(Sampler.parentBased(Sampler.traceIdRatioBased(properties.getSampleProbability())))
                .setSpanLimits(SpanLimits.builder().setMaxNumberOfAttributes(8).setMaxAttributeValueLength(160)
                        // SDK 1.43 requires positive limits. This writer never adds events or links.
                        .setMaxNumberOfEvents(1).setMaxNumberOfLinks(1).build())
                .addSpanProcessor(BatchSpanProcessor.builder(spanExporter).setMaxQueueSize(MAX_QUEUE)
                        .setMaxExportBatchSize(MAX_BATCH).setScheduleDelay(EXPORT_DELAY)
                        .setExporterTimeout(EXPORT_TIMEOUT).build()).build();
        var logs = SdkLoggerProvider.builder().setResource(resource)
                .setLogLimits(() -> LogLimits.builder().setMaxNumberOfAttributes(8).setMaxAttributeValueLength(160).build())
                .addLogRecordProcessor(BatchLogRecordProcessor.builder(logExporter).setMaxQueueSize(MAX_QUEUE)
                        .setMaxExportBatchSize(MAX_BATCH).setScheduleDelay(EXPORT_DELAY)
                        .setExporterTimeout(EXPORT_TIMEOUT).build()).build();
        return new SafeTelemetryRuntime(traces, logs);
    }

    Tracer tracer(String scope) { return traces.get(scope); }
    Logger logger(String scope) { return logs.get(scope); }

    void flush() {
        traces.forceFlush().join(3, TimeUnit.SECONDS);
        logs.forceFlush().join(3, TimeUnit.SECONDS);
    }

    @Override public void close() {
        traces.shutdown().join(3, TimeUnit.SECONDS);
        logs.shutdown().join(3, TimeUnit.SECONDS);
    }
}
