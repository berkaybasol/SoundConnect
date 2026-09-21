package com.berkayb.soundconnect.modules.notification.helper;

import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class NotificationBadgeCacheHelperIT {
	
	// Redis 7
	@Container
	static final GenericContainer<?> REDIS =
			new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
	
	private LettuceConnectionFactory connectionFactory;
	private StringRedisTemplate stringRedisTemplate;
	private NotificationBadgeCacheHelper helper;
	
	private UUID userId;
	
	@BeforeEach
	void setup() {
		// This integration test needs only its disposable Redis. Do not inherit
		// application/test auto-configuration or accidentally use the live cache.
		connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
		connectionFactory.afterPropertiesSet();
		connectionFactory.start();
		stringRedisTemplate = new StringRedisTemplate(connectionFactory);
		stringRedisTemplate.afterPropertiesSet();
		helper = new NotificationBadgeCacheHelper(stringRedisTemplate);
		userId = UUID.randomUUID();
	}

	@AfterEach
	void close() {
		if (connectionFactory != null) connectionFactory.destroy();
	}
	
	@Test
	@DisplayName("setUnread → getCacheUnread ve TTL ayarlanmış olmalı")
	void setUnread_and_ttl() {
		helper.setUnread(userId, 42);
		
		Long cached = helper.getCacheUnread(userId);
		assertThat(cached).isEqualTo(42L);
		
		// TTL > 0 olmalı (süresiz değil)
		Long ttlSeconds = stringRedisTemplate.getExpire(helper.unreadKey(userId));
		assertThat(ttlSeconds).isNotNull();
		assertThat(ttlSeconds).isGreaterThan(0);
		
		// Helper'daki sabit TTL'le tür uyumu kontrol (sadece varlığını kıyaslayalım)
		Duration configured = NotificationBadgeCacheHelper.getUnreadCountTtl();
		assertThat(configured).isPositive();
	}
	
	@Test
	@DisplayName("decrementUnreadSafely: cache yoksa mutasyon sonrası freshUnread değerini kullanır")
	void decrement_when_cache_absent_uses_fresh() {
		// başlangıçta cache yok
		helper.decrementUnreadSafely(userId, /*n=*/5, /*freshUnread=*/3);
		
		Long cached = helper.getCacheUnread(userId);
		// freshUnread, veritabanındaki mutasyon sonrası güncel değerdir; tekrar
		// azaltmak aynı bildirimi iki kez düşürürdü.
		assertThat(cached).isEqualTo(3L);
	}
	
	@Test
	@DisplayName("decrementUnreadSafely: cache varken doğru azalt, 0'ın altına düşürme")
	void decrement_when_cache_present_bounds_to_zero() {
		helper.setUnread(userId, 10);
		
		helper.decrementUnreadSafely(userId, 4, /*freshUnread (önemsiz çünkü cache var)*/ 999);
		assertThat(helper.getCacheUnread(userId)).isEqualTo(6L);
		
		helper.decrementUnreadSafely(userId, 10, 999); // 6 - 10 => 0
		assertThat(helper.getCacheUnread(userId)).isEqualTo(0L);
	}
	
	@Test
	@DisplayName("setUnread: negatif değer verilse bile 0'a sabitlenir")
	void setUnread_never_negative() {
		helper.setUnread(userId, -7);
		assertThat(helper.getCacheUnread(userId)).isEqualTo(0L);
	}
}
