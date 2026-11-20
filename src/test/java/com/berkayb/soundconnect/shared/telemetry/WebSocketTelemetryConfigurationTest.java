package com.berkayb.soundconnect.shared.telemetry;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.WebSocketMessageBrokerStats;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.server.support.WebSocketHttpRequestHandler;

import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebSocketTelemetryConfigurationTest {
    @Test void optInRequiresApiServletAndNeverAddsWorkerCapabilities() {
        var web = new WebApplicationContextRunner().withUserConfiguration(WebSocketTelemetryConfiguration.class);
        web.run(context -> assertThat(context).doesNotHaveBean(WebSocketMeterBinder.class));
        web.withPropertyValues("app.telemetry.metrics.enabled=true", "app.telemetry.metrics.service-name=soundconnect-media-worker")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(WebSocketMeterBinder.class);
                    assertThat(context).doesNotHaveBean("webSocketTelemetryConfigurer");
                });
        new ApplicationContextRunner().withUserConfiguration(WebSocketTelemetryConfiguration.class)
                .withPropertyValues("app.telemetry.metrics.enabled=true", "app.telemetry.metrics.service-name=soundconnect-api")
                .run(context -> assertThat(context).doesNotHaveBean(WebSocketMeterBinder.class));
    }

    @Test void realDelegatingWebSocketConfigurationStartsWithoutCycleAndDecoratesOnlyExistingHandler() {
        new WebApplicationContextRunner().withUserConfiguration(ExistingWebSocketFixture.class, WebSocketTelemetryConfiguration.class)
                .withPropertyValues("app.telemetry.metrics.enabled=true", "app.telemetry.metrics.service-name=soundconnect-api")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(WebSocketMeterBinder.class)
                            .hasSingleBean(WebSocketMessageBrokerStats.class);
                    var registry = context.getBean(SimpleMeterRegistry.class);
                    context.getBean(WebSocketMeterBinder.class).bindTo(registry);
                    assertThat(registry.get("soundconnect.websocket.observed").gauge().value()).isEqualTo(1);
                    var mapping = context.getBean("stompWebSocketHandlerMapping", SimpleUrlHandlerMapping.class);
                    assertThat(mapping.getUrlMap()).containsOnlyKeys("/existing-fixture-ws");
                    var handler = ((WebSocketHttpRequestHandler) mapping.getUrlMap().get("/existing-fixture-ws")).getWebSocketHandler();
                    var session = mock(WebSocketSession.class);
                    when(session.getId()).thenReturn("private-session-id");
                    when(session.getAcceptedProtocol()).thenReturn("v12.stomp");
                    when(session.getAttributes()).thenReturn(new ConcurrentHashMap<>());
                    when(session.isOpen()).thenReturn(true);
                    assertThatCode(() -> {
                        handler.afterConnectionEstablished(session);
                        assertThat(registry.get("soundconnect.websocket.sessions.active").gauge().value()).isEqualTo(1);
                        assertThat(registry.get("soundconnect.websocket.sessions.opened").functionCounter().count()).isEqualTo(1);
                        handler.afterConnectionClosed(session, CloseStatus.NORMAL);
                        handler.afterConnectionClosed(session, CloseStatus.NORMAL);
                    }).doesNotThrowAnyException();
                    assertThat(registry.get("soundconnect.websocket.sessions.active").gauge().value()).isZero();
                    assertThat(registry.get("soundconnect.websocket.sessions.closed").functionCounter().count()).isEqualTo(1);
                    assertThat(registry.getMeters()).hasSize(5);
                    assertThat(registry.getMeters().toString()).doesNotContain("private-session-id", "existing-fixture-ws");
                });
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebSocketMessageBroker
    static class ExistingWebSocketFixture implements WebSocketMessageBrokerConfigurer {
        @Bean SimpleMeterRegistry fixtureMeters() { return new SimpleMeterRegistry(); }
        @Override public void registerStompEndpoints(StompEndpointRegistry registry) { registry.addEndpoint("/existing-fixture-ws"); }
        @Override public void configureMessageBroker(MessageBrokerRegistry registry) { registry.enableSimpleBroker("/topic"); }
    }
}
