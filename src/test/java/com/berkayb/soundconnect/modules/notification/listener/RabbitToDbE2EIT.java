package com.berkayb.soundconnect.modules.notification.listener;

import com.berkayb.soundconnect.modules.notification.config.NotificationRabbitConfig;
import com.berkayb.soundconnect.modules.notification.helper.NotificationBadgeCacheHelper;
import com.berkayb.soundconnect.modules.notification.mail.MailNotificationService;
import com.berkayb.soundconnect.modules.notification.repository.NotificationRepository;
import com.berkayb.soundconnect.modules.notification.websocket.NotificationWebSocketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

@Testcontainers
@SpringBootTest(classes = RabbitToDbE2E.TestApp.class)
@ImportAutoConfiguration(RabbitAutoConfiguration.class)
@TestPropertySource(properties = {
		// Rabbit yapılandırman
		"app.messaging.notification.exchange=notification.exchange",
		"app.messaging.notification.queue=notification.queue",
		"app.messaging.notification.routingKey=notification.#",
		"app.messaging.notification.dlxExchange=notification.dlx",
		"app.messaging.notification.dlq=notification.queue.dlq",
		"spring.rabbitmq.listener.simple.default-requeue-rejected=false",
		// JPA/Redis için gereksizleri kapatma / dummy
		"spring.flyway.enabled=false",
		"spring.liquibase.enabled=false",
		"SOUNDCONNECT_JWT_SECRETKEY=dummy", "app.jwt.secret=dummy"
})
class RabbitToDbE2E {
	
	@SpringBootConfiguration
	@EnableAutoConfiguration
	@EntityScan(basePackages = {
			"com.berkayb.soundconnect.modules.notification.entity",
			"com.berkayb.soundconnect.shared.entity"
	})
	@EnableJpaRepositories(basePackages = "com.berkayb.soundconnect.modules.notification.repository")
	@Import({
			NotificationRabbitConfig.class,
			NotificationEventListener.class,
			NotificationBadgeCacheHelper.class,
			com.berkayb.soundconnect.modules.notification.mapper.NotificationMapperImpl.class
	})
	static class TestApp {
		@Bean
		Jackson2JsonMessageConverter jackson2JsonMessageConverter() {
			return new Jackson2JsonMessageConverter();
		}
	}
	
	// --- Testcontainers ---
	@Container
	static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-alpine");
	@Container static final PostgreSQLContainer<?> POSTGRES =
			new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("sc").withUsername("sc").withPassword("sc");
	@Container static final GenericContainer<?> REDIS =
			new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
	
	@DynamicPropertySource
	static void props(DynamicPropertyRegistry r) {
		r.add("spring.rabbitmq.host", RABBIT::getHost);
		r.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
		r.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
		r.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
		r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		r.add("spring.datasource.username", POSTGRES::getUsername);
		r.add("spring.datasource.password", POSTGRES::getPassword);
		r.add("spring.data.redis.host", REDIS::getHost);
		r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
		r.add("spring.data.redis.ssl.enabled", () -> false);
	}
	
	@Autowired
	RabbitTemplate rabbitTemplate;
	@Autowired
	NotificationRepository repo;
	@Autowired NotificationBadgeCacheHelper badge;
	@Autowired
	StringRedisTemplate redis;
	
	// WS & Mail’i mock’la (yan etkiyi doğrulamak istemiyoruz burada)
	@MockitoBean
	NotificationWebSocketService ws;
	@MockitoBean
	MailNotificationService mail;
	
	@BeforeEach
	void clean() {
		repo.deleteAll();
		redis.getConnectionFactory().getConnection().serverCommands().flushAll();
	}
	
	@Test
	void message_persisted_and_unread_cached() {
		UUID user = UUID.randomUUID();
		
		var event = com.berkayb.soundconnect.shared.messaging.events.notification.NotificationInboundEvent.builder()
		                                                                                                  .recipientId(user)
		                                                                                                  .type(com.berkayb.soundconnect.modules.notification.enums.NotificationType.MEDIA_UPLOAD_RECEIVED)
		                                                                                                  .title("Yükleme alındı")
		                                                                                                  .message("Parça ulaştı")
		                                                                                                  .payload(Map.of("recipientEmail", "user@example.com"))
		                                                                                                  .emailForce(null)
		                                                                                                  .build();
		
		rabbitTemplate.convertAndSend("notification.exchange", "notification.media.upload", event);
		
		// repo’ya gerçekten yazıldı mı?
		org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).untilAsserted(() -> {
			assertThat(repo.countByRecipientIdAndReadIsFalse(user)).isEqualTo(1L);
			assertThat(badge.getCacheUnread(user)).isEqualTo(1L);
		});
	}
}