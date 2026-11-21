package com.berkayb.soundconnect.shared.telemetry;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.WebSocketMessageBrokerStats;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.messaging.SubProtocolWebSocketHandler;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.ToDoubleFunction;

/** Passive, identifier-free observations of the existing Spring WebSocket/SockJS transport. */
public final class WebSocketMeterBinder implements MeterBinder, SmartInitializingSingleton {
    private static final String CLOSED_ATTRIBUTE = WebSocketMeterBinder.class.getName() + ".closed";
    private final ObjectProvider<WebSocketMessageBrokerStats> stats;
    private final AtomicLong closed = new AtomicLong();
    private volatile boolean initialized;

    WebSocketMeterBinder(ObjectProvider<WebSocketMessageBrokerStats> stats) { this.stats = stats; }

    @Override public void afterSingletonsInstantiated() {
        // Broker stats depend on the configurer list. Never resolve them while
        // constructing configurers, the registry, or meter binders.
        initialized = true;
    }

    @Override public void bindTo(MeterRegistry registry) {
        safely(() -> Gauge.builder("soundconnect.websocket.observed", this, source -> source.currentStats() == null ? 0 : 1)
                .strongReference(true).register(registry));
        safely(() -> Gauge.builder("soundconnect.websocket.sessions.active", this, WebSocketMeterBinder::active)
                .strongReference(true).register(registry));
        safely(() -> FunctionCounter.builder("soundconnect.websocket.sessions.opened", this,
                        source -> source.read(SubProtocolWebSocketHandler.Stats::getTotalSessions)).register(registry));
        safely(() -> FunctionCounter.builder("soundconnect.websocket.sessions.closed", closed, AtomicLong::doubleValue).register(registry));
        safely(() -> FunctionCounter.builder("soundconnect.websocket.transport.errors", this,
                        source -> source.read(SubProtocolWebSocketHandler.Stats::getTransportErrorSessions)).register(registry));
    }

    /** This is transport activity, including SockJS fallbacks, not authenticated user count. */
    private double active() {
        return read(value -> {
            int websocket = value.getWebSocketSessions();
            int streaming = value.getHttpStreamingSessions();
            int polling = value.getHttpPollingSessions();
            return websocket < 0 || streaming < 0 || polling < 0 ? Double.NaN : (double) websocket + streaming + polling;
        });
    }

    private double read(ToDoubleFunction<SubProtocolWebSocketHandler.Stats> measurement) {
        try {
            var current = currentStats();
            if (current == null) return Double.NaN;
            double value = measurement.applyAsDouble(current);
            return Double.isFinite(value) && value >= 0 ? value : Double.NaN;
        } catch (RuntimeException unavailable) { return Double.NaN; }
    }

    private SubProtocolWebSocketHandler.Stats currentStats() {
        if (!initialized) return null;
        try {
            var source = stats.getIfAvailable();
            return source == null ? null : source.getWebSocketSessionStats();
        } catch (RuntimeException unavailable) { return null; }
    }

    WebSocketHandler decorate(WebSocketHandler delegate) {
        return new WebSocketHandlerDecorator(delegate) {
            @Override public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
                try { super.afterConnectionClosed(session, status); }
                finally { observeClosed(session); }
            }
        };
    }

    private void observeClosed(WebSocketSession session) {
        safely(() -> {
            // Lifetime is exactly the existing session's lifetime. No parallel
            // session-ID map, listener dedup cache, timer, or cleanup task exists.
            Map<String, Object> attributes = session.getAttributes();
            AtomicBoolean marker;
            synchronized (attributes) {
                Object before = attributes.get(CLOSED_ATTRIBUTE);
                if (before == null) {
                    marker = new AtomicBoolean();
                    attributes.put(CLOSED_ATTRIBUTE, marker);
                } else if (before instanceof AtomicBoolean value) marker = value;
                else return;
            }
            if (marker.compareAndSet(false, true)) closed.incrementAndGet();
        });
    }

    private static void safely(Runnable action) {
        try { action.run(); } catch (RuntimeException ignored) { /* Never alter transport behavior or log input. */ }
    }
}
