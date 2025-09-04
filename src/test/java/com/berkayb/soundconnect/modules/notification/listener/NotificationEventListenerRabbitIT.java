package com.berkayb.soundconnect.modules.notification.listener;

import com.berkayb.soundconnect.modules.notification.config.NotificationRabbitConfig;
import com.berkayb.soundconnect.modules.notification.dto.response.NotificationResponseDto;
import com.berkayb.soundconnect.modules.notification.entity.Notification;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.notification.helper.NotificationBadgeCacheHelper;
import com.berkayb.soundconnect.modules.notification.mail.MailNotificationService;
import com.berkayb.soundconnect.modules.notification.mapper.NotificationMapper;
import com.berkayb.soundconnect.modules.notification.repository.NotificationRepository;
import com.berkayb.soundconnect.modules.notification.websocket.NotificationWebSocketService;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationInboundEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.annotation.DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD;

@Testcontainers
@SpringBootTest(classes = {
		NotificationRabbitConfig.class,                  // exchange/queue/binding
		NotificationEventListener.class,                 // dinleyen sınıf
		NotificationEventListenerRabbitIT.AmqpTestConfig.class
})
@ImportAutoConfiguration(RabbitAutoConfiguration.class)  // << RabbitTemplate + ConnectionFactory + @EnableRabbit
@TestPropertySource(properties = {
		"app.messaging.notification.exchange=notification.exchange",
		"app.messaging.notification.queue=notification.queue",
		"app.messaging.notification.routingKey=notification.#",
		"app.messaging.notification.dlxExchange=notification.dlx",
		"app.messaging.notification.dlq=notification.queue.dlq",
		// requeue istemiyoruz; testte hata durumunda kuyruk dolmasın
		"spring.rabbitmq.listener.simple.default-requeue-rejected=false",
		// dummy JWT (her ihtimale karşı)
		"SOUNDCONNECT_JWT_SECRETKEY=dummy",
		"app.jwt.secret=dummy"
})
@org.springframework.test.annotation.DirtiesContext(classMode = BEFORE_EACH_TEST_METHOD)
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
	
	// Yan etkileri doğrulamak için mock’lar
	@MockBean NotificationRepository notificationRepository;
	@MockBean NotificationBadgeCacheHelper badgeCacheHelper;
	@MockBean NotificationMapper notificationMapper;
	@MockBean NotificationWebSocketService notificationWebSocketService;
	@MockBean MailNotificationService mailNotificationService;
	
	@Test
	@DisplayName("RabbitMQ → Listener: event tüketilir; save + cache + WS + mail tetiklenir")
	void consume_event_from_queue_and_invoke_side_effects() {
		UUID userId = UUID.randomUUID();
		
		Notification persisted = Notification.builder()
		                                     .recipientId(userId)
		                                     .type(NotificationType.MEDIA_UPLOAD_RECEIVED)
		                                     .title("Yükleme alındı")
		                                     .message("Parça ulaştı")
		                                     .payload(Map.of("recipientEmail","user@example.com"))
		                                     .read(false)
		                                     .build();
		UUID notifId = UUID.randomUUID();
		org.springframework.test.util.ReflectionTestUtils.setField(persisted, "id", notifId);
		
		when(notificationRepository.save(any(Notification.class))).thenReturn(persisted);
		when(notificationRepository.countByRecipientIdAndReadIsFalse(userId)).thenReturn(5L);
		
		NotificationResponseDto dto = new NotificationResponseDto(
				notifId, userId, NotificationType.MEDIA_UPLOAD_RECEIVED,
				"Yükleme alındı", "Parça ulaştı", false, null, Map.of("recipientEmail","user@example.com")
		);
		when(notificationMapper.toDto(persisted)).thenReturn(dto);
		when(badgeCacheHelper.getCacheUnread(userId)).thenReturn(5L);
		
		NotificationInboundEvent event = NotificationInboundEvent.builder()
		                                                         .recipientId(userId)
		                                                         .type(NotificationType.MEDIA_UPLOAD_RECEIVED)
		                                                         .title("Yükleme alındı")
		                                                         .message("Parça ulaştı")
		                                                         .payload(Map.of("recipientEmail","user@example.com"))
		                                                         .emailForce(null)
		                                                         .build();
		
		rabbitTemplate.convertAndSend("notification.exchange", "notification.media.upload", event);
		
		ArgumentCaptor<Notification> toSaveCap = ArgumentCaptor.forClass(Notification.class);
		verify(notificationRepository, timeout(5000)).save(toSaveCap.capture());
		Notification toSave = toSaveCap.getValue();
		assertThat(toSave.getRecipientId()).isEqualTo(userId);
		assertThat(toSave.getType()).isEqualTo(NotificationType.MEDIA_UPLOAD_RECEIVED);
		assertThat(toSave.isRead()).isFalse();
		
		verify(notificationRepository, timeout(5000)).countByRecipientIdAndReadIsFalse(userId);
		verify(badgeCacheHelper, timeout(5000)).setUnreadWithTtl(userId, 5L);
		
		verify(notificationMapper, timeout(5000)).toDto(persisted);
		verify(notificationWebSocketService, timeout(5000)).sendNotificationToUser(userId, dto);
		verify(notificationWebSocketService, timeout(5000)).sendUnreadBadgeToUser(userId, 5L);
		
		verify(mailNotificationService, timeout(5000)).maybeSendNotificationEmail(persisted, null);
		
		verifyNoMoreInteractions(notificationWebSocketService, mailNotificationService);
	}
	
	@Test
	@DisplayName("Geçersiz event (type=null) → hiçbir yan etki tetiklenmez")
	void invalid_event_is_skipped() {
		UUID userId = UUID.randomUUID();
		NotificationInboundEvent bad = NotificationInboundEvent.builder()
		                                                       .recipientId(userId)
		                                                       .type(null)
		                                                       .title("x").message("y").payload(Map.of()).build();
		
		rabbitTemplate.convertAndSend("notification.exchange", "notification.any", bad);
		
		verifyNoInteractions(notificationRepository, badgeCacheHelper, notificationMapper,
		                     notificationWebSocketService, mailNotificationService);
	}
}