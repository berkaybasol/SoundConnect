package com.berkayb.soundconnect.shared.telemetry.tracing;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.web.filter.ServerHttpObservationFilter;

import java.time.Duration;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A deliberately small allowlist export from the existing HTTP observation.
 * It never registers a global SDK, instruments SQL/clients, reads incoming trace
 * headers, activates a thread context, or forwards application stdout.
 */
public final class SafeHttpObservationHandler implements ObservationHandler<ServerRequestObservationContext>, AutoCloseable {
    static final int MAX_QUEUE = SafeTelemetryRuntime.MAX_QUEUE;
    static final int MAX_BATCH = SafeTelemetryRuntime.MAX_BATCH;
    static final Duration EXPORT_TIMEOUT = SafeTelemetryRuntime.EXPORT_TIMEOUT;
    private static final String SCOPE = "soundconnect.safe-http";
    private static final Object SPAN_KEY = new Object();
    private static final Object ERROR_KEY = new Object();
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS", "TRACE", "CONNECT");
    private static final Pattern ROUTE = Pattern.compile("/[A-Za-z0-9/{}._*:-]{0,159}");
    private final SafeTelemetryRuntime runtime;
    private final boolean ownsRuntime;
    private final Tracer tracer;
    private final Logger logger;

    SafeHttpObservationHandler(SafeTelemetryRuntime runtime, boolean ownsRuntime) {
        this.runtime = runtime;
        this.ownsRuntime = ownsRuntime;
        this.tracer = runtime.tracer(SCOPE);
        this.logger = runtime.logger(SCOPE);
    }

    static SafeHttpObservationHandler create(SafeHttpTelemetryProperties properties) {
        return new SafeHttpObservationHandler(SafeTelemetryRuntime.create(properties, "soundconnect-api"), true);
    }

    static Span ownedRequestSpan(HttpServletRequest request) {
        return ServerHttpObservationFilter.findObservationContext(request)
                .map(context -> (Span) context.get(SPAN_KEY)).orElse(null);
    }

    @Override public boolean supportsContext(Observation.Context context) {
        return context instanceof ServerRequestObservationContext;
    }

    @Override public void onStart(ServerRequestObservationContext context) {
        try {
            // Do not use a remote/user-supplied parent or other active SDK context.
            Span span = tracer.spanBuilder("HTTP request").setNoParent().setSpanKind(SpanKind.SERVER).startSpan();
            context.put(SPAN_KEY, span);
        } catch (RuntimeException ignored) { /* Telemetry must never reject a request. */ }
    }

    @Override public void onStop(ServerRequestObservationContext context) {
        Span span = context.get(SPAN_KEY);
        if (span == null) return;
        try {
            if (!span.getSpanContext().isSampled()) return;
            String method = safeMethod(context.getCarrier().getMethod());
            String route = safeRoute(context.getPathPattern());
            int status = context.getResponse() == null ? 0 : context.getResponse().getStatus();
            String outcome = outcome(status);
            SafeExceptionMetadata error = context.get(ERROR_KEY);
            if (error == null && context.getError() != null) error = SafeExceptionMetadata.from(context.getError());
            span.updateName(method + " " + route);
            span.setAttribute("http.request.method", method).setAttribute("http.route", route).setAttribute("http.outcome", outcome);
            if (status >= 500 || context.getError() != null) span.setStatus(StatusCode.ERROR);
            if (error != null) {
                span.setAttribute("exception.type", error.type()).setAttribute("code.file.name", error.file())
                        .setAttribute("code.line.number", error.line());
            }
            if (status >= 400 || error != null) {
                var record = logger.logRecordBuilder().setContext(Context.root().with(span))
                        .setSeverity(status >= 500 ? Severity.ERROR : Severity.WARN)
                        .setBody("HTTP request failed")
                        .setAttribute(AttributeKey.stringKey("http.request.method"), method)
                        .setAttribute(AttributeKey.stringKey("http.route"), route)
                        .setAttribute(AttributeKey.stringKey("http.outcome"), outcome);
                if (error != null) {
                    record.setAttribute(AttributeKey.stringKey("exception.type"), error.type())
                            .setAttribute(AttributeKey.stringKey("code.file.name"), error.file())
                            .setAttribute(AttributeKey.longKey("code.line.number"), error.line());
                }
                record.emit();
            }
        } catch (RuntimeException ignored) { /* Bounded exporters run off the request thread. */ }
        finally {
            try { span.end(); } catch (RuntimeException ignored) { }
            context.remove(SPAN_KEY);
            context.remove(ERROR_KEY);
        }
    }

    /** Advice-resolved exceptions are otherwise invisible to the HTTP observation. */
    public static void recordException(HttpServletRequest request, Throwable error) {
        try {
            ServerHttpObservationFilter.findObservationContext(request)
                    .ifPresent(context -> {
                        Span span = context.get(SPAN_KEY);
                        if (span != null && span.getSpanContext().isSampled()) {
                            context.put(ERROR_KEY, SafeExceptionMetadata.from(error));
                        }
                    });
        } catch (RuntimeException ignored) { }
    }

    public static String exceptionSummary(Throwable error) {
        try {
            SafeExceptionMetadata safe = SafeExceptionMetadata.from(error);
            return safe.type() + " " + safe.file() + ":" + safe.line();
        } catch (RuntimeException ignored) { return "UNKNOWN"; }
    }

    public static String safeRoute(HttpServletRequest request) {
        return ServerHttpObservationFilter.findObservationContext(request)
                .map(context -> safeRoute(context.getPathPattern())).orElse("UNKNOWN");
    }

    static String safeRoute(String pathPattern) {
        return pathPattern != null && ROUTE.matcher(pathPattern).matches() ? pathPattern : "UNKNOWN";
    }

    static String safeMethod(String method) { return METHODS.contains(method) ? method : "OTHER"; }

    static String outcome(int status) {
        return switch (status / 100) {
            case 1 -> "INFORMATIONAL";
            case 2 -> "SUCCESS";
            case 3 -> "REDIRECTION";
            case 4 -> "CLIENT_ERROR";
            case 5 -> "SERVER_ERROR";
            default -> "UNKNOWN";
        };
    }

    void flush() { runtime.flush(); }

    @Override public void close() { if (ownsRuntime) runtime.close(); }
}
