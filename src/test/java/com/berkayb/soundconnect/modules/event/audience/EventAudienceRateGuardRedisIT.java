package com.berkayb.soundconnect.modules.event.audience;

import com.berkayb.soundconnect.shared.exception.RateLimitedException;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** Standalone Redis client wired only to the disposable mapped container port; no app configuration. */
@Testcontainers @Timeout(30)
class EventAudienceRateGuardRedisIT {
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7-alpine").withExposedPorts(6379).withReuse(false);
    LettuceConnectionFactory factory; StringRedisTemplate redis; EventAudienceRateGuard guard;
    @BeforeEach void setup() {
        assertThat(REDIS.isRunning()).isTrue();
        factory=new LettuceConnectionFactory(REDIS.getHost(),REDIS.getMappedPort(6379)); factory.afterPropertiesSet(); factory.start();
        redis=new StringRedisTemplate(factory); redis.afterPropertiesSet(); guard=new EventAudienceRateGuard(redis);
    }
    @AfterEach void close() { factory.destroy(); }
    @Test void fixedWindowIsAccountScopedAndRejectedAttemptsDoNotExtendItsExpiry() {
        UUID actor=UUID.randomUUID(); String key="soundconnect:event-intent:user:"+actor;
        for(int i=0;i<60;i++) guard.check(actor);
        Long before=redis.getExpire(key,TimeUnit.MILLISECONDS);
        var failure=catchThrowableOfType(() -> guard.check(actor),RateLimitedException.class);
        assertThat(failure.getRetryAfterSeconds()).isBetween(1L,60L);
        assertThat(redis.getExpire(key,TimeUnit.MILLISECONDS)).isPositive().isLessThanOrEqualTo(before);
        assertThat(redis.opsForValue().get(key)).isEqualTo("60");
        guard.check(UUID.randomUUID());
    }
    @Test void concurrentRequestsCannotExceedSixtyAcceptedMutations() throws Exception {
        UUID actor=UUID.randomUUID(); var start=new CountDownLatch(1); var results=new ArrayList<Future<Boolean>>();
        try(var executor=Executors.newFixedThreadPool(16)) {
            for(int i=0;i<90;i++) results.add(executor.submit(() -> {
                start.await(); try {guard.check(actor); return true;} catch(RateLimitedException limited) {return false;}
            }));
            start.countDown(); int accepted=0;
            for(var result:results) if(result.get(15,TimeUnit.SECONDS)) accepted++;
            assertThat(accepted).isEqualTo(60);
            assertThat(redis.opsForValue().get("soundconnect:event-intent:user:"+actor)).isEqualTo("60");
        }
    }
}
