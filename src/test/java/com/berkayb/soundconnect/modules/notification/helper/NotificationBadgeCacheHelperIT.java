package com.berkayb.soundconnect.modules.notification.helper;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.redis.DataRedisTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@DataRedisTest
@Import(NotificationBadgeCacheHelper.class)
class NotificationBadgeCacheHelperIT {
	
	// Redis 7
	@Container
	static final GenericContainer<?> REDIS =
			new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
	
	@DynamicPropertySource
	static void props(DynamicPropertyRegistry r) {
		r.add("spring.data.redis.host", () -> REDIS.getHost());
		r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
		r.add("spring.data.redis.ssl.enabled", () -> false);
	}
	
	@Autowired
	private StringRedisTemplate stringRedisTemplate;
	
	@Autowired
	private NotificationBadgeCacheHelper helper;
	
	private UUID userId;
	
	@BeforeEach
	void setup() {
		userId = UUID.randomUUID();
		// temiz başlangıç
		stringRedisTemplate.getConnectionFactory()
		                   .getConnection()
		                   .serverCommands()
		                   .flushAll();
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
	@DisplayName("decrementUnreadSafely: cache yoksa freshUnread kullan, negatif olmasın")
	void decrement_when_cache_absent_uses_fresh() {
		// başlangıçta cache yok
		helper.decrementUnreadSafely(userId, /*n=*/5, /*freshUnread=*/3);
		
		Long cached = helper.getCacheUnread(userId);
		// 3 - 5 -> 0'a sabitlenmeli
		assertThat(cached).isEqualTo(0L);
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