package com.berkayb.soundconnect.shared.recovery;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** New quarantine boundaries only; no closed restore acceptance suite is rerun. */
@IsolatedRecoveryTest
class RecoveryRuntimeBoundaryTest {
    @BeforeAll static void hold() { RecoveryRuntime.quarantine(); }

    @ParameterizedTest @ValueSource(strings={"/api/v1/auth/login", "/api/v1/auth/register",
            "/api/v1/admin/notifications/campaigns", "/actuator/health", "/ws"})
    void httpNeverReachesSecurityRedisOrController(String path) throws Exception {
        var request = new MockHttpServletRequest("POST", path);
        var response = new MockHttpServletResponse();
        var calls = new AtomicInteger();
        new RecoveryHttpFilter().doFilter(request, response, (a,b) -> calls.incrementAndGet());
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("\"canApply\":false", "RECOVERY_HOLD");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(calls.get()).isZero();
    }

    @Test void allInboundAndOutboundStompCommandsBlockedExceptDisconnect() {
        var interceptor = new RecoveryChannelInterceptor();
        for (var command : StompCommand.values()) {
            var message = MessageBuilder.createMessage(new byte[0], StompHeaderAccessor.create(command).getMessageHeaders());
            if (command == StompCommand.DISCONNECT) assertThat(interceptor.preSend(message, null)).isSameAs(message);
            else assertThatThrownBy(() -> interceptor.preSend(message, null)).isInstanceOf(MessageDeliveryException.class);
        }
        assertThatThrownBy(() -> interceptor.preSend(MessageBuilder.withPayload("outbound").build(), null))
                .isInstanceOf(MessageDeliveryException.class);
    }

    @ParameterizedTest @ValueSource(strings={"OPEN", "hold", "", "REVIEW"})
    void invalidModeCannotEnableWriter(String mode) {
        try (var context = new GenericApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("input", Map.of(
                    "app.recovery.mode", mode, "spring.jpa.hibernate.ddl-auto", "validate")));
            assertThatThrownBy(() -> new RecoveryRuntimeInitializer().initialize(context))
                    .hasMessageContaining("MODE_REQUIRED_OPEN_UNSUPPORTED");
            assertThat(RecoveryRuntime.isHeld()).isTrue();
        }
    }

    @Test void heldProcessCannotResetByStartingUnconfiguredSecondContext() {
        try (var context = new GenericApplicationContext()) {
            assertThatThrownBy(() -> new RecoveryRuntimeInitializer().initialize(context))
                    .hasMessageContaining("MODE_REQUIRED");
        }
        assertThatThrownBy(() -> RecoveryRuntime.guard(() -> fail("egress"))).isInstanceOf(RecoveryRuntime.Held.class);
    }

    @Test void workerReadinessCannotRemainGreen(@TempDir Path directory) throws Exception {
        var dependency = mock(com.berkayb.soundconnectworker.MediaWorkerDependencyHealthIndicator.class);
        var marker = directory.resolve("owned.ready"); Files.writeString(marker, "1");
        var health = new com.berkayb.soundconnectworker.MediaWorkerHealthFile(dependency, marker.toString());
        health.onReady(); health.refresh();
        assertThat(marker).doesNotExist(); verifyNoInteractions(dependency);
    }

    @ParameterizedTest @ValueSource(strings={
        "modules.collab.outbox.CollabNotificationOutboxDispatcher#dispatch",
        "modules.follow.outbox.FollowNotificationOutboxDispatcher#dispatch",
        "modules.event.performer.outbox.EventPerformerNotificationOutboxDispatcher#dispatch",
        "modules.overthinking.outbox.OverthinkingNotificationOutboxDispatcher#dispatch",
        "modules.studio.reservation.outbox.StudioReservationNotificationOutboxDispatcher#dispatch",
        "modules.tablegroup.notification.outbox.TableGroupNotificationOutboxDispatcher#dispatch",
        "modules.application.mailintent.ApplicationMailDispatcher#dispatchBatch",
        "modules.venue.suggestion.VenueSuggestionDispatcher#dispatchBatch",
        "modules.media.transcode.MediaTranscodeDispatcher#dispatchOne",
        "modules.media.transcode.MediaHlsCleanupDispatcher#clean",
        "modules.media.image.ImageVariantJobDispatcher#process",
        "modules.media.abuse.MediaUploadCleanupWorker#clean",
        "modules.media.deletion.MediaDeletionWorker#delete",
        "modules.notification.dlq.NotificationDlqOperations#replay",
        "modules.notification.campaign.CampaignWorker#process",
        "modules.notification.push.PushDispatcher#poll",
        "modules.notification.push.transport.FcmHttpV1Transport#send",
        "modules.notification.listener.NotificationEventListener#handle",
        "modules.media.transcode.VideoHlsListener#onMessage",
        "shared.mail.consumer.MailJobConsumer#listenMailJobs",
        "shared.mail.producer.MailProducerImpl#send",
        "shared.mail.producer.MailRetryPublisher#publishWithDelay",
        "shared.mail.adapter.MailSenderClientImpl#send",
        "shared.messaging.events.notification.NotificationProducer#publish",
        "shared.messaging.events.notification.NotificationProducer#publishConfirmed"})
    void jobAndEgressEntryRefusesBeforeClaimAckOrProvider(String target) throws Exception {
        String[] names = target.split("#");
        Class<?> type = Class.forName("com.berkayb.soundconnect." + names[0]);
        // No constructor or external dependency is invoked. Real method must stop
        // before dereferencing any repository/channel/client or claiming a row.
        Object instance = mock(type, CALLS_REAL_METHODS);
        List<Method> methods = Arrays.stream(type.getDeclaredMethods()).filter(m -> m.getName().equals(names[1])).toList();
        assertThat(methods).isNotEmpty();
        for (var method : methods) {
            method.setAccessible(true);
            Object[] arguments = Arrays.stream(method.getParameterTypes()).map(RecoveryRuntimeBoundaryTest::emptyArgument).toArray();
            assertThatThrownBy(() -> {
                try { method.invoke(instance, arguments); }
                catch (InvocationTargetException e) { throw e.getCause(); }
            }).as(target).isInstanceOf(RecoveryRuntime.Held.class);
        }
    }

    private static Object emptyArgument(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == long.class) return 0L;
        if (type == int.class) return 0;
        return null;
    }
}
