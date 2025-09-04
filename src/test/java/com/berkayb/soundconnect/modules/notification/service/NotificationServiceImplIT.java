package com.berkayb.soundconnect.modules.notification.service;

import com.berkayb.soundconnect.modules.notification.entity.Notification;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.notification.helper.NotificationBadgeCacheHelper;
import com.berkayb.soundconnect.modules.notification.mapper.NotificationMapperImpl;
import com.berkayb.soundconnect.modules.notification.repository.NotificationRepository;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.annotation.DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD;

@Testcontainers
@SpringBootTest(classes = NotificationServiceImplIT.TestConfig.class)
@TestPropertySource(properties = {
		// DB & JPA
		"spring.jpa.hibernate.ddl-auto=update",
		"spring.jpa.properties.hibernate.jdbc.lob.non_contextual_creation=true",
		"spring.flyway.enabled=false",
		"spring.liquibase.enabled=false",
		
		// Bu IT’de AMQP, WS, OAuth, Media vs kullanmıyoruz
		"spring.rabbitmq.listener.simple.auto-startup=false",
		
		// JWT/OAuth dummy (her ihtimale karşı)
		"SOUNDCONNECT_JWT_SECRETKEY=dummy",
		"app.jwt.secret=dummy",
		"GOOGLE_CLIENT_ID=dummy",
		"GOOGLE_CLIENT_SECRET=dummy",
		"spring.security.oauth2.client.registration.google.client-id=dummy",
		"spring.security.oauth2.client.registration.google.client-secret=dummy"
})
@org.springframework.test.annotation.DirtiesContext(classMode = BEFORE_EACH_TEST_METHOD)
class NotificationServiceImplIT {
	
	/** ------- Yalın Boot Uygulaması: sadece Notification modülü + JPA/Redis auto-config ------- */
	@SpringBootConfiguration
	@EnableAutoConfiguration // DataSource, JPA, Redis vs. auto-config’leri aç
	@EntityScan(basePackages = {
			"com.berkayb.soundconnect.modules.notification.entity",
			"com.berkayb.soundconnect.shared.entity"
	})
	@EnableJpaRepositories(basePackages = {
			"com.berkayb.soundconnect.modules.notification.repository"
	})
	@Import({
			NotificationServiceImpl.class,          // service
			NotificationBadgeCacheHelper.class,     // redis helper
			NotificationMapperImpl.class            // mapstruct impl
	})
	static class TestConfig { }
	
	// ---------- Testcontainers: Postgres + Redis ----------
	@Container
	static final PostgreSQLContainer<?> POSTGRES =
			new PostgreSQLContainer<>("postgres:16-alpine")
					.withDatabaseName("soundconnect_test")
					.withUsername("sc")
					.withPassword("sc");
	
	@Container
	static final GenericContainer<?> REDIS =
			new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
	
	@DynamicPropertySource
	static void props(DynamicPropertyRegistry r) {
		// DB
		r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		r.add("spring.datasource.username", POSTGRES::getUsername);
		r.add("spring.datasource.password", POSTGRES::getPassword);
		// Redis
		r.add("spring.data.redis.host", REDIS::getHost);
		r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
		r.add("spring.data.redis.ssl.enabled", () -> false);
	}
	
	// ---- Güvenlik/OAuth bean’lerini mock’la ki context sade kalsın (scana girmeseler de zarar vermez) ----
	@MockitoBean
	private com.berkayb.soundconnect.auth.security.JwtAuthenticationFilter jwtAuthenticationFilter;
	@MockitoBean private com.berkayb.soundconnect.auth.security.JwtTokenProvider jwtTokenProvider;
	@MockitoBean private com.berkayb.soundconnect.auth.service.GoogleAuthService googleAuthService;
	
	@Autowired private NotificationRepository repo;
	@Autowired private NotificationService notificationService;
	@Autowired private StringRedisTemplate redis;
	@Autowired private NotificationBadgeCacheHelper badgeHelper;
	
	private final UUID user = UUID.randomUUID();
	
	@BeforeEach
	void clean() {
		repo.deleteAll();
		redis.getConnectionFactory().getConnection().serverCommands().flushAll();
	}
	
	private Notification make(UUID u, NotificationType t, boolean read, String title, String msg) {
		Notification n = Notification.builder()
		                             .recipientId(u)
		                             .type(t)
		                             .title(title)
		                             .message(msg)
		                             .payload(Map.of("recipientEmail","user@example.com"))
		                             .read(read)
		                             .build();
		org.springframework.test.util.ReflectionTestUtils.setField(n, "createdAt", LocalDateTime.now());
		return repo.save(n);
	}
	
	@Test
	@DisplayName("getUnreadCount: cache miss → DB; ikinci çağrı cache hit")
	void getUnreadCount_caches_then_uses_cache() {
		make(user, NotificationType.MEDIA_UPLOAD_RECEIVED, false, "u1", "m1");
		make(user, NotificationType.AUTH_EMAIL_VERIFIED, false, "u2", "m2");
		make(user, NotificationType.SOCIAL_NEW_FOLLOWER, true,  "r1", "read");
		
		long first = notificationService.getUnreadCount(user);
		assertThat(first).isEqualTo(2L);
		
		Long cached = badgeHelper.getCacheUnread(user);
		assertThat(cached).isEqualTo(2L);
		
		// DB’ye yeni unread eklesek de cache sabit kalmalı
		make(user, NotificationType.MEDIA_TRANSCODE_READY, false, "u3", "m3");
		long second = notificationService.getUnreadCount(user);
		assertThat(second).isEqualTo(2L);
	}
	
	@Test
	@DisplayName("markAsRead: DB update + cache güvenli 1 azalt")
	void markAsRead_updates_db_and_cache() {
		Notification n1 = make(user, NotificationType.MEDIA_UPLOAD_RECEIVED, false, "u1", "m1");
		make(user, NotificationType.AUTH_EMAIL_VERIFIED, false, "u2", "m2");
		assertThat(notificationService.getUnreadCount(user)).isEqualTo(2L); // cache seed
		
		notificationService.markAsRead(user, n1.getId());
		
		Optional<Notification> reloaded = repo.findById(n1.getId());
		assertThat(reloaded).isPresent();
		assertThat(reloaded.get().isRead()).isTrue();
		
		assertThat(badgeHelper.getCacheUnread(user)).isEqualTo(1L);
	}
	
	@Test
	@DisplayName("markAllAsRead: tüm unread okunur; cache 0’a setlenir")
	void markAllAsRead_sets_cache_zero() {
		make(user, NotificationType.MEDIA_UPLOAD_RECEIVED, false, "u1", "m1");
		make(user, NotificationType.AUTH_EMAIL_VERIFIED, false, "u2", "m2");
		make(user, NotificationType.SOCIAL_NEW_FOLLOWER, true, "r1", "read");
		assertThat(notificationService.getUnreadCount(user)).isEqualTo(2L);
		
		int updated = notificationService.markAllAsRead(user);
		assertThat(updated).isGreaterThanOrEqualTo(2);
		assertThat(repo.countByRecipientIdAndReadIsFalse(user)).isZero();
		assertThat(badgeHelper.getCacheUnread(user)).isEqualTo(0L);
	}
	
	@Test
	@DisplayName("deleteById: unread silinirse cache 1 azalır; olmayan id NOT_FOUND atar")
	void deleteById_unread_decrements_cache_and_notfound_throws() {
		Notification unread = make(user, NotificationType.MEDIA_TRANSCODE_READY, false, "u", "m");
		Notification read   = make(user, NotificationType.SOCIAL_NEW_FOLLOWER, true,  "r", "m");
		assertThat(notificationService.getUnreadCount(user)).isEqualTo(1L); // cache seed
		
		boolean ok = notificationService.deleteById(user, unread.getId());
		assertThat(ok).isTrue();
		assertThat(badgeHelper.getCacheUnread(user)).isEqualTo(0L);
		assertThat(repo.findById(unread.getId())).isEmpty();
		
		assertThatThrownBy(() -> notificationService.deleteById(user, UUID.randomUUID()))
				.isInstanceOf(SoundConnectException.class);
	}
}