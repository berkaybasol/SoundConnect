package com.berkayb.soundconnect.modules.notification.listener;

import com.berkayb.soundconnect.modules.notification.config.NotificationRabbitConfig;
import com.berkayb.soundconnect.modules.notification.dto.response.NotificationResponseDto;
import com.berkayb.soundconnect.modules.notification.entity.Notification;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.notification.helper.NotificationBadgeCacheHelper;
import com.berkayb.soundconnect.modules.notification.mapper.NotificationMapper;
import com.berkayb.soundconnect.modules.notification.repository.NotificationRepository;
import com.berkayb.soundconnect.modules.notification.repository.NotificationReceiptRepository;
import com.berkayb.soundconnect.modules.notification.service.NotificationService;
import com.berkayb.soundconnect.modules.notification.websocket.NotificationWebSocketService;
import com.berkayb.soundconnect.shared.mail.producer.MailProducer;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationInboundEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.dao.DataIntegrityViolationException;
import com.berkayb.soundconnect.modules.notification.service.NotificationDeliveryPolicy;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import static org.awaitility.Awaitility.await;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = {
		NotificationRabbitConfig.class,                  // exchange/queue/binding
		NotificationEventListener.class,                 // dinleyen sınıf
		com.berkayb.soundconnect.support.DeliveryPolicyTestSupport.Config.class,
		NotificationEventListenerRabbitIT.AmqpTestConfig.class
})
@ImportAutoConfiguration(RabbitAutoConfiguration.class)
@TestPropertySource(properties = {
		"spring.config.location=classpath:/application-test.yml", "spring.config.import=",
		"spring.autoconfigure.exclude=",
		"spring.rabbitmq.listener.simple.auto-startup=true",
		"spring.rabbitmq.listener.direct.auto-startup=true",
		"app.messaging.notification.exchange=notification.exchange",
		"app.messaging.notification.queue=notification.queue",
		"app.messaging.notification.routingKey=notification.#",
		"app.messaging.notification.dlxExchange=notification.dlx",
		"app.messaging.notification.dlq=notification.queue.dlq",
		"SOUNDCONNECT_JWT_SECRETKEY=test-jwt-secret-key-at-least-32-bytes-long",
		"app.jwt.secret=test-jwt-secret-key-at-least-32-bytes-long"
})
@org.springframework.test.annotation.DirtiesContext(classMode = AFTER_EACH_TEST_METHOD)
class NotificationEventListenerRabbitIT {
	
	@Container
	static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-alpine");
	
	@DynamicPropertySource
	static void rabbitProps(DynamicPropertyRegistry r) {
		r.add("spring.rabbitmq.host", RABBIT::getHost);
		r.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
		r.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
		r.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
	}
	
	@Configuration
	static class AmqpTestConfig {
		@Bean
		Jackson2JsonMessageConverter jackson2JsonMessageConverter() {
			return new Jackson2JsonMessageConverter();
		}
	}
	
	@Autowired
	RabbitTemplate rabbitTemplate;
	@Autowired RabbitAdmin rabbitAdmin;
	@Autowired NotificationDeliveryPolicy policy;
	
	// Yan etkileri doğrulamak için mock’lar
	@MockitoBean NotificationRepository notificationRepository;
	@MockitoBean NotificationReceiptRepository receiptRepository;
	@MockitoBean NotificationBadgeCacheHelper badgeCacheHelper;
	@MockitoBean NotificationMapper notificationMapper;
	@MockitoBean NotificationWebSocketService notificationWebSocketService;
	@MockitoBean MailProducer mailProducer; // refactor sonrası
	@MockitoBean NotificationService notificationService;
	@MockitoSpyBean NotificationEventListener listener;
	private CountDownLatch consumed;

	@BeforeEach
	void observeCompletedDelivery() {
		consumed = new CountDownLatch(1);
		doAnswer(invocation -> {
			try {
				return invocation.callRealMethod();
			} finally {
				consumed.countDown();
			}
		}).when(listener).handle(any(NotificationInboundEvent.class));
	}
	
	@Test
	@DisplayName("RabbitMQ → Listener: event tüketilir; save + cache + WS + mail tetiklenir")
	void consume_event_from_queue_and_invoke_side_effects() throws InterruptedException {
		when(notificationService.refreshActorIdentityForDelivery(any())).thenAnswer(call -> call.getArgument(0));
		when(receiptRepository.claim(any(), any())).thenReturn(1);
		UUID userId = UUID.randomUUID();
		Instant occurredAt = Instant.parse("2026-08-11T10:00:00Z");
		
		Notification persisted = Notification.builder()
		                                     .recipientId(userId)
		                                     .type(NotificationType.MEDIA_TRANSCODE_FAILED) // emailRecommended=true
		                                     .title("Medya işleme başarısız")
		                                     .message("Parça işlenemedi")
		                                     .occurredAt(occurredAt)
		                                     .payload(Map.of("recipientEmail", "user@example.com"))
		                                     .read(false)
		                                     .build();
		UUID notifId = UUID.randomUUID();
		org.springframework.test.util.ReflectionTestUtils.setField(persisted, "id", notifId);
		
		when(notificationRepository.saveAndFlush(any(Notification.class))).thenReturn(persisted);
		when(notificationRepository.countByRecipientIdAndReadIsFalse(userId)).thenReturn(5L);
		
		NotificationResponseDto dto = new NotificationResponseDto(
				notifId, userId, NotificationType.MEDIA_TRANSCODE_FAILED,
				"Medya işleme başarısız", "Parça işlenemedi", false, null, Map.of("recipientEmail", "user@example.com")
		);
		when(notificationMapper.toDto(persisted)).thenReturn(dto);
		
		NotificationInboundEvent event = NotificationInboundEvent.builder().eventId(UUID.randomUUID())
		                                                         .recipientId(userId)
		                                                         .type(NotificationType.MEDIA_TRANSCODE_FAILED) // mail gönderilmesi garanti
		                                                         .title("Medya işleme başarısız")
		                                                         .message("Parça işlenemedi")
		                                                         .payload(Map.of("recipientEmail", "user@example.com"))
		                                                         .emailForce(null)
		                                                         .occurredAt(occurredAt)
		                                                         .build();
		
		rabbitTemplate.convertAndSend("notification.exchange", "notification.media.failed", event);
		assertThat(consumed.await(5, TimeUnit.SECONDS)).as("listener completed the broker delivery").isTrue();
		verify(receiptRepository).claim(event.eventId(), userId);
		
		ArgumentCaptor<Notification> toSaveCap = ArgumentCaptor.forClass(Notification.class);
		verify(notificationRepository, timeout(5000)).saveAndFlush(toSaveCap.capture());
		Notification toSave = toSaveCap.getValue();
		assertThat(toSave.getRecipientId()).isEqualTo(userId);
		assertThat(toSave.getType()).isEqualTo(NotificationType.MEDIA_TRANSCODE_FAILED);
		assertThat(toSave.isRead()).isFalse();
		assertThat(toSave.getOccurredAt()).isEqualTo(occurredAt);
		
		verify(notificationRepository, timeout(5000)).countByRecipientIdAndReadIsFalse(userId);
		verify(badgeCacheHelper, timeout(5000)).setUnreadWithTtl(userId, 5L);
		
		verify(notificationMapper, timeout(5000)).toDto(persisted);
		verify(notificationWebSocketService, timeout(5000)).sendNotificationToUser(userId, dto);
		verify(notificationWebSocketService, timeout(5000)).sendUnreadBadgeToUser(userId, 5L);
		verify(badgeCacheHelper, never()).getCacheUnread(any());
		
		// mailProducer çağrılmalı
		verify(mailProducer, timeout(5000)).send(any());
		
		verifyNoMoreInteractions(notificationWebSocketService, mailProducer);
	}
	
	@Test
	@DisplayName("Geçersiz event (type=null) → DLQ içinde korunur; yan etki yok")
	void invalidEventIsRetainedInDlqWithoutSideEffects() throws InterruptedException {
		UUID userId = UUID.randomUUID();
		NotificationInboundEvent bad = NotificationInboundEvent.builder().eventId(UUID.randomUUID())
		                                                       .recipientId(userId)
		                                                       .type(null)
		                                                       .title("x").message("y").payload(Map.of()).build();
		
		rabbitTemplate.convertAndSend("notification.exchange", "notification.any", bad);
		assertThat(consumed.await(5, TimeUnit.SECONDS)).as("invalid event was actually consumed").isTrue();
		verify(listener).handle(bad);
		assertThat(rabbitTemplate.receiveAndConvert("notification.queue.dlq", 5_000)).isEqualTo(bad);
		
		verifyNoInteractions(notificationRepository, badgeCacheHelper, notificationMapper,
		                     notificationWebSocketService, mailProducer, receiptRepository, notificationService);
	}

	@Test
	void deepPayloadGoesToDlqOnceAndFollowingHealthyEventStillProgressesWithoutPrivateLogs() {
		String privateValue = "PRIVATE_NOTIFICATION_SENTINEL@example.invalid";
		Map<String,Object> payload = Map.of("secret", privateValue);
		for (int depth = 0; depth < 10; depth++) payload = Map.of("nested", payload);
		var bad = event(payload);
		var healthy = event(Map.of());
		var realPolicy = new NotificationDeliveryPolicy(null, null, null, null);
		when(policy.eligible(bad)).thenAnswer(call -> realPolicy.eligible(call.getArgument(0)));
		allowPersistence();
		var logs = captureLogs();
		try {
			rabbitTemplate.convertAndSend("notification.exchange", "notification.event", bad);
			rabbitTemplate.convertAndSend("notification.exchange", "notification.event", healthy);
			await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> verify(receiptRepository).claim(healthy.eventId(), healthy.recipientId()));
			assertThat(rabbitTemplate.receiveAndConvert("notification.queue.dlq", 5_000)).isEqualTo(bad);
			verify(listener, times(1)).handle(bad);
			verify(receiptRepository, never()).claim(bad.eventId(), bad.recipientId());
			assertNoPrivateLogs(logs, privateValue);
		} finally { stopLogs(logs); }
	}

	@Test
	void temporaryDatabaseFailureRetriesWithBackoffThenAcknowledgesExactlyOneInboxWrite() {
		var event = event(Map.of());
		var attempts = new AtomicInteger();
		var attemptTimes = new CopyOnWriteArrayList<Long>();
		when(policy.eligible(event)).thenAnswer(call -> {
			attemptTimes.add(System.nanoTime());
			if (attempts.incrementAndGet() < 3) throw new TransientDataAccessResourceException("fixture database unavailable");
			return true;
		});
		allowPersistence();
		rabbitTemplate.convertAndSend("notification.exchange", "notification.event", event);
		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> verify(receiptRepository).claim(event.eventId(), event.recipientId()));
		assertThat(attempts).hasValue(3);
		assertThat(Duration.ofNanos(attemptTimes.get(1) - attemptTimes.get(0))).isGreaterThanOrEqualTo(Duration.ofMillis(900));
		assertThat(Duration.ofNanos(attemptTimes.get(2) - attemptTimes.get(1))).isGreaterThanOrEqualTo(Duration.ofMillis(1_900));
		verify(notificationRepository, times(1)).saveAndFlush(any());
		assertThat(rabbitTemplate.receive("notification.queue.dlq", 200)).isNull();
	}

	@Test
	void exhaustedFailureHasFourAttemptsRetainsOriginalInDlqAndDoesNotBlockHealthyEvent() {
		String privateValue = "PRIVATE_DM_TEXT_TOKEN_SENTINEL";
		var bad = event(Map.of("private", privateValue));
		var healthy = event(Map.of());
		var attempts = new AtomicInteger();
		var attemptTimes = new CopyOnWriteArrayList<Long>();
		when(policy.eligible(bad)).thenAnswer(call -> {
			attemptTimes.add(System.nanoTime());
			attempts.incrementAndGet();
			throw new TransientDataAccessResourceException(privateValue);
		});
		allowPersistence();
		var logs = captureLogs();
		try {
			rabbitTemplate.convertAndSend("notification.exchange", "notification.event", bad);
			rabbitTemplate.convertAndSend("notification.exchange", "notification.event", healthy);
			await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> verify(receiptRepository).claim(healthy.eventId(), healthy.recipientId()));
			assertThat(rabbitTemplate.receiveAndConvert("notification.queue.dlq", 5_000)).isEqualTo(bad);
			assertThat(attempts).hasValue(4);
			assertThat(Duration.ofNanos(attemptTimes.get(3) - attemptTimes.get(0))).isGreaterThanOrEqualTo(Duration.ofMillis(6_800));
			verify(receiptRepository, never()).claim(bad.eventId(), bad.recipientId());
			assertNoPrivateLogs(logs, privateValue);
			assertThat(rabbitTemplate.receive("notification.queue.dlq", 200)).isNull();
		} finally { stopLogs(logs); }
	}

	@Test
	void permanentDatabaseFailureDoesNotConsumeTheRetryBudget() {
		var event = event(Map.of());
		when(policy.eligible(event)).thenThrow(new DataIntegrityViolationException("fixture constraint"));
		rabbitTemplate.convertAndSend("notification.exchange", "notification.event", event);
		assertThat(rabbitTemplate.receiveAndConvert("notification.queue.dlq", 5_000)).isEqualTo(event);
		verify(listener, times(1)).handle(event);
		verifyNoInteractions(receiptRepository, notificationRepository);
	}

	@Test
	void malformedJsonIsRetainedWithItsBodyButNotWrittenIntoErrorLogs() {
		String privateValue = "PRIVATE_BROKEN_JSON_EMAIL_TOKEN";
		var properties = new MessageProperties();
		properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
		properties.setHeader("__TypeId__", NotificationInboundEvent.class.getName());
		var bad = new Message(("{\"secret\":\"" + privateValue).getBytes(StandardCharsets.UTF_8), properties);
		var logs = captureLogs();
		try {
			rabbitTemplate.send("notification.exchange", "notification.event", bad);
			var retained = rabbitTemplate.receive("notification.queue.dlq", 5_000);
			assertThat(retained).isNotNull();
			assertThat(retained.getBody()).containsExactly(bad.getBody());
			verifyNoInteractions(receiptRepository, notificationRepository);
			assertNoPrivateLogs(logs, privateValue);
		} finally { stopLogs(logs); }
	}

	private NotificationInboundEvent event(Map<String,Object> payload) {
		return NotificationInboundEvent.builder().eventId(UUID.randomUUID()).recipientId(UUID.randomUUID())
				.type(NotificationType.SOCIAL_NEW_FOLLOWER).title("fixture").message("fixture")
				.payload(payload).emailForce(false).occurredAt(Instant.now()).build();
	}

	private void allowPersistence() {
		when(receiptRepository.claim(any(), any())).thenReturn(1);
		when(notificationRepository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
	}

	private static ListAppender<ILoggingEvent> captureLogs() {
		var appender = new ListAppender<ILoggingEvent>();
		appender.list = new CopyOnWriteArrayList<>();
		appender.start();
		((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).addAppender(appender);
		return appender;
	}
	private static void stopLogs(ListAppender<ILoggingEvent> appender) {
		((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(appender);
		appender.stop();
	}
	private static void assertNoPrivateLogs(ListAppender<ILoggingEvent> appender, String secret) {
		assertThat(appender.list).isNotEmpty();
		assertThat(appender.list).allSatisfy(event -> {
			assertThat(event.getFormattedMessage()).doesNotContain(secret);
			if (event.getThrowableProxy() != null) assertThat(ThrowableProxyUtil.asString(event.getThrowableProxy())).doesNotContain(secret);
		});
	}}
