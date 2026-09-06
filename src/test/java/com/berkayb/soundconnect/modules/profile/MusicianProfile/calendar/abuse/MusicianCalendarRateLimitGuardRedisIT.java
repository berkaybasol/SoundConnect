package com.berkayb.soundconnect.modules.profile.MusicianProfile.calendar.abuse;

import com.berkayb.soundconnect.shared.exception.RateLimitedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.redis.DataRedisTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@DataRedisTest
@Import({MusicianCalendarRateLimitConfiguration.class, MusicianCalendarRateLimitGuard.class})
@Timeout(30)
class MusicianCalendarRateLimitGuardRedisIT {
	@Container
	static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("spring.data.redis.host", REDIS::getHost);
		registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
		registry.add("spring.data.redis.ssl.enabled", () -> false);
	}
	@Autowired MusicianCalendarRateLimitGuard guard;
	@Autowired StringRedisTemplate redis;

	@Test
	void concurrentRequestsAcrossNodesCannotExceedSharedBurst() throws Exception {
		UUID userId = UUID.randomUUID();
		var secondNode = new MusicianCalendarRateLimitGuard(redis, new MusicianCalendarRateLimitProperties());
		var start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(12)) {
			List<Future<Boolean>> attempts = new ArrayList<>();
			for (int i = 0; i < 12; i++) {
				var node = i % 2 == 0 ? guard : secondNode;
				attempts.add(executor.submit(() -> {
					start.await();
					try { node.check(userId); return true; }
					catch (RateLimitedException e) { return false; }
				}));
			}
			start.countDown();
			int allowed = 0;
			for (var attempt : attempts) if (attempt.get(10, TimeUnit.SECONDS)) allowed++;
			assertThat(allowed).isEqualTo(3);
		}
	}

	@Test
	void exhaustedBucketIsUserScopedAndShortLived() {
		UUID userId = UUID.randomUUID();
		for (int i = 0; i < 3; i++) guard.check(userId);
		assertThatThrownBy(() -> guard.check(userId)).isInstanceOfSatisfying(RateLimitedException.class,
				e -> assertThat(e.getRetryAfterSeconds()).isBetween(1L, 10L));
		guard.check(UUID.randomUUID());
		assertThat(redis.getExpire("soundconnect:musician-profile:calendar-rate-limit:user:" + userId)).isBetween(1L, 60L);
	}

	@Test
	void foundersShareTheSameBandBucketWithoutSpendingThePersonalBucket() {
		UUID bandId = UUID.randomUUID();
		var otherFounderNode = new MusicianCalendarRateLimitGuard(redis, new MusicianCalendarRateLimitProperties());
		guard.checkBand(bandId);
		otherFounderNode.checkBand(bandId);
		guard.checkBand(bandId);
		assertThatThrownBy(() -> otherFounderNode.checkBand(bandId)).isInstanceOf(RateLimitedException.class);
		guard.check(bandId);
		guard.checkBand(UUID.randomUUID());
		assertThat(redis.getExpire("soundconnect:musician-profile:calendar-rate-limit:band:" + bandId)).isBetween(1L, 60L);
	}
}
