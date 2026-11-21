package com.berkayb.soundconnect.shared.telemetry;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.WebSocketMessageBrokerStats;
import org.springframework.web.socket.messaging.SubProtocolWebSocketHandler;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebSocketMeterBinderTest {
    @Test void usesTypedLiveStatsAndExactlyFiveIdentifierFreeMeters() {
        var source = mock(WebSocketMessageBrokerStats.class);
        var stats = mock(SubProtocolWebSocketHandler.Stats.class);
        when(source.getWebSocketSessionStats()).thenReturn(stats);
        when(stats.getWebSocketSessions()).thenReturn(3);
        when(stats.getHttpStreamingSessions()).thenReturn(2);
        when(stats.getHttpPollingSessions()).thenReturn(1);
        when(stats.getTotalSessions()).thenReturn(20);
        when(stats.getTransportErrorSessions()).thenReturn(4);
        var binder = initialized(provider(source));
        var registry = new SimpleMeterRegistry();
        try {
            binder.bindTo(registry);
            assertThat(registry.getMeters()).hasSize(5).allSatisfy(meter -> {
                assertThat(meter.getId().getTags()).isEmpty();
                assertThat(meter.getId().getDescription()).isNull();
            });
            assertThat(registry.get("soundconnect.websocket.observed").gauge().value()).isEqualTo(1);
            assertThat(registry.get("soundconnect.websocket.sessions.active").gauge().value()).isEqualTo(6);
            assertThat(registry.get("soundconnect.websocket.sessions.opened").functionCounter().count()).isEqualTo(20);
            assertThat(registry.get("soundconnect.websocket.transport.errors").functionCounter().count()).isEqualTo(4);
            assertThat(closed(registry)).isZero();
            when(stats.getWebSocketSessions()).thenReturn(1);
            assertThat(registry.get("soundconnect.websocket.sessions.active").gauge().value()).isEqualTo(4);
            verify(source, never()).getWebSocketSessionStatsInfo();
            verify(source, never()).getStompSubProtocolStatsInfo();
        } finally { registry.close(); }
    }

    @Test void startupMissingStatsAndInvalidValuesRemainUnknownWithoutEarlyBeanResolution() {
        var source = mock(WebSocketMessageBrokerStats.class);
        var provider = provider(source);
        var binder = new WebSocketMeterBinder(provider);
        var registry = new SimpleMeterRegistry();
        try {
            binder.bindTo(registry);
            assertThat(registry.get("soundconnect.websocket.observed").gauge().value()).isZero();
            assertThat(registry.get("soundconnect.websocket.sessions.active").gauge().value()).isNaN();
            verifyNoInteractions(provider);
            binder.afterSingletonsInstantiated();
            assertThat(registry.get("soundconnect.websocket.sessions.opened").functionCounter().count()).isNaN();
            assertThat(registry.get("soundconnect.websocket.transport.errors").functionCounter().count()).isNaN();
            var stats = mock(SubProtocolWebSocketHandler.Stats.class);
            when(source.getWebSocketSessionStats()).thenReturn(stats);
            when(stats.getWebSocketSessions()).thenReturn(-1);
            when(stats.getTotalSessions()).thenReturn(-1);
            assertThat(registry.get("soundconnect.websocket.sessions.active").gauge().value()).isNaN();
            assertThat(registry.get("soundconnect.websocket.sessions.opened").functionCounter().count()).isNaN();
            when(source.getWebSocketSessionStats()).thenThrow(new IllegalStateException("private-stats-failure"));
            assertThat(registry.get("soundconnect.websocket.observed").gauge().value()).isZero();
            assertThat(registry.get("soundconnect.websocket.sessions.active").gauge().value()).isNaN();
        } finally { registry.close(); }
    }

    @Test void delegatesAllTransportMethodsAndCountsDuplicateCloseOnlyOnceEvenWhenDelegateThrows() throws Exception {
        var binder = initialized(provider(null));
        var registry = new SimpleMeterRegistry();
        try {
            binder.bindTo(registry);
            var delegate = mock(WebSocketHandler.class);
            var session = session();
            var handler = binder.decorate(delegate);
            var message = new TextMessage("private-message-body");
            var failure = new IOException("private-transport-reason");
            var status = new CloseStatus(1006, "private-close-reason");
            when(delegate.supportsPartialMessages()).thenReturn(true);
            handler.afterConnectionEstablished(session);
            handler.handleMessage(session, message);
            handler.handleTransportError(session, failure);
            assertThat(handler.supportsPartialMessages()).isTrue();
            doThrow(failure).when(delegate).afterConnectionClosed(session, status);
            assertThatThrownBy(() -> handler.afterConnectionClosed(session, status)).isSameAs(failure);
            assertThatThrownBy(() -> handler.afterConnectionClosed(session, status)).isSameAs(failure);
            assertThat(closed(registry)).isEqualTo(1);
            verify(delegate).afterConnectionEstablished(session);
            verify(delegate).handleMessage(session, message);
            verify(delegate).handleTransportError(session, failure);
            verify(delegate).supportsPartialMessages();
            verify(delegate, times(2)).afterConnectionClosed(session, status);
            verifyNoMoreInteractions(delegate);
            assertThat(registry.getMeters().toString()).doesNotContain("private-", "1006");
            verify(session, never()).getId();
            verify(session, never()).getPrincipal();
            verify(session, never()).getUri();
            verify(session, never()).getHandshakeHeaders();
        } finally { registry.close(); }
    }

    @Test void concurrentCloseCallbacksKeepOneMarkerPerExistingSessionAndDoNotMergeSessions() throws Exception {
        var binder = initialized(provider(null));
        var registry = new SimpleMeterRegistry();
        try (var executor = Executors.newFixedThreadPool(4)) {
            binder.bindTo(registry);
            var delegate = mock(WebSocketHandler.class);
            var handler = binder.decorate(delegate);
            var session = session();
            var start = new CountDownLatch(1);
            var tasks = IntStream.range(0, 16).mapToObj(index -> executor.submit(() -> {
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                handler.afterConnectionClosed(session, CloseStatus.NORMAL);
                return null;
            })).toList();
            start.countDown();
            for (var task : tasks) task.get(5, TimeUnit.SECONDS);
            assertThat(closed(registry)).isEqualTo(1);
            assertThat(session.getAttributes()).hasSize(1);
            handler.afterConnectionClosed(session(), CloseStatus.SERVER_ERROR);
            assertThat(closed(registry)).isEqualTo(2);
            verify(delegate, times(16)).afterConnectionClosed(session, CloseStatus.NORMAL);
        } finally { registry.close(); }
    }

    @Test void brokenObservationStorageAndMeterRegistrationCannotReplaceProductException() throws Exception {
        var binder = initialized(provider(null));
        assertThatCode(() -> binder.bindTo(mock(MeterRegistry.class))).doesNotThrowAnyException();
        var delegate = mock(WebSocketHandler.class);
        var handler = binder.decorate(delegate);
        var session = mock(WebSocketSession.class);
        when(session.getAttributes()).thenThrow(new IllegalStateException("private-observation-error"));
        assertThatCode(() -> handler.afterConnectionClosed(session, CloseStatus.NORMAL)).doesNotThrowAnyException();
        var failure = new IOException("original-product-error");
        doThrow(failure).when(delegate).afterConnectionClosed(session, CloseStatus.SERVER_ERROR);
        assertThatThrownBy(() -> handler.afterConnectionClosed(session, CloseStatus.SERVER_ERROR)).isSameAs(failure);
    }

    private static double closed(SimpleMeterRegistry registry) {
        return registry.get("soundconnect.websocket.sessions.closed").functionCounter().count();
    }
    private static WebSocketMeterBinder initialized(ObjectProvider<WebSocketMessageBrokerStats> provider) {
        var binder = new WebSocketMeterBinder(provider); binder.afterSingletonsInstantiated(); return binder;
    }
    @SuppressWarnings("unchecked") private static ObjectProvider<WebSocketMessageBrokerStats> provider(WebSocketMessageBrokerStats source) {
        var provider = mock(ObjectProvider.class); when(provider.getIfAvailable()).thenReturn(source); return provider;
    }
    private static WebSocketSession session() {
        var session = mock(WebSocketSession.class);
        when(session.getAttributes()).thenReturn(new ConcurrentHashMap<>());
        return session;
    }
}
