package com.berkayb.soundconnect.shared.telemetry.tracing;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** No free-form data crosses this boundary. It never activates an OTel context. */
public final class SafeDependencyTelemetry {
    public static final SafeDependencyTelemetry NOOP = new SafeDependencyTelemetry(null, null);
    private static final String SCOPE = "soundconnect.safe-dependency";
    private final Tracer tracer;
    private final MeterRegistry registry;

    SafeDependencyTelemetry(SafeTelemetryRuntime runtime, MeterRegistry registry) {
        this.tracer = runtime == null ? null : runtime.tracer(SCOPE);
        this.registry = registry;
    }

    public enum Operation {
        MAIL_SEND("mailersend", "send"), FCM_SEND("fcm", "send"),
        S3_HEAD_BUCKET("s3", "head_bucket"), S3_HEAD_OBJECT("s3", "head_object"),
        S3_COPY_OBJECT("s3", "copy_object"), S3_DELETE_OBJECT("s3", "delete_object"),
        S3_LIST_OBJECTS("s3", "list_objects"), S3_DELETE_OBJECTS("s3", "delete_objects"),
        S3_LIST_VERSIONS("s3", "list_versions"),
        S3_PUT_OBJECT("s3", "put_object"), S3_OPEN_OBJECT("s3", "open_object"),
        S3_DOWNLOAD_OBJECT("s3", "download_object"), CLOUDFRONT_INVALIDATE("cloudfront", "invalidate");
        private final String provider;
        private final String operation;
        Operation(String provider, String operation) { this.provider = provider; this.operation = operation; }
        private boolean submission() { return this == MAIL_SEND || this == FCM_SEND; }
    }

    /** Only this class can create a parent, and only from our own HTTP observation. */
    public static final class Parent {
        private final SpanContext context;
        private Parent(SpanContext context) { this.context = context; }
    }

    public Parent captureRequestParent() {
        if (tracer == null) return null;
        try {
            if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
                Span span = SafeHttpObservationHandler.ownedRequestSpan(attributes.getRequest());
                if (span != null) return new Parent(span.getSpanContext());
            }
        } catch (RuntimeException ignored) { }
        return null;
    }

    public Call start(Operation operation) { return start(operation, captureRequestParent()); }

    public Call start(Operation operation, Parent parent) {
        if (tracer == null || operation == null) return Call.NOOP;
        try {
            var builder = tracer.spanBuilder(operation.provider + " " + operation.operation).setSpanKind(SpanKind.CLIENT);
            if (parent == null) builder.setNoParent();
            else builder.setParent(Context.root().with(Span.wrap(parent.context)));
            Span span = builder.startSpan();
            return new Call(operation, span, registry, System.nanoTime());
        } catch (RuntimeException ignored) { return Call.NOOP; }
    }

    /** For provider operations whose successful synchronous return means success. */
    public <T> T observe(Operation operation, Supplier<T> action) {
        try (Call call = start(operation)) {
            T value = action.get();
            call.succeeded();
            return value;
        }
    }

    public static final class Call implements AutoCloseable {
        private static final Call NOOP = new Call(null, null, null, 0);
        private final Operation operation;
        private final Span span;
        private final MeterRegistry registry;
        private final long start;
        private final AtomicBoolean closed = new AtomicBoolean();
        private String result = "error";
        private Call(Operation operation, Span span, MeterRegistry registry, long start) {
            this.operation = operation; this.span = span; this.registry = registry; this.start = start;
        }
        /** SEND is accepted by a provider; this does not claim recipient delivery. */
        public void succeeded() {
            if (operation != null) result = operation.submission() ? "accepted" : "success";
        }
        @Override public void close() {
            if (operation == null || !closed.compareAndSet(false, true)) return;
            try {
                Timer.builder("soundconnect.dependency.duration")
                        .tags("provider", operation.provider, "operation", operation.operation, "result", result)
                        .register(registry).record(Math.max(0, System.nanoTime() - start), TimeUnit.NANOSECONDS);
            } catch (RuntimeException ignored) { /* Export/registration must not affect the provider call. */ }
            try {
                span.setAttribute("dependency.provider", operation.provider)
                        .setAttribute("dependency.operation", operation.operation).setAttribute("dependency.result", result);
                if ("error".equals(result)) span.setStatus(StatusCode.ERROR);
            } catch (RuntimeException ignored) { }
            finally { try { span.end(); } catch (RuntimeException ignored) { } }
        }
    }
}
