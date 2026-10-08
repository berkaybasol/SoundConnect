package com.berkayb.soundconnect.modules.notification.push;

import com.berkayb.soundconnect.shared.exception.RateLimitedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** Explicit disposable Redis only; no application configuration or real account keys. */
@EnabledIfSystemProperty(named="push.test.redis-url",matches="redis://127\\.0\\.0\\.1:[0-9]+")
class PushDeviceRateLimitRedisTest {
    @Test void concurrentInstancesShareAtomicLimitAndExpiryRestoresAccess() throws Exception {
        URI uri=URI.create(System.getProperty("push.test.redis-url"));
        var connection=new LettuceConnectionFactory(uri.getHost(),uri.getPort()); connection.afterPropertiesSet();
        var redis=new StringRedisTemplate(connection);
        var properties=new PushProperties(); properties.setMaxDeviceMutationsPerMinute(60);
        var first=new PushDeviceRateLimit(redis,properties); var second=new PushDeviceRateLimit(redis,properties);
        var owner=UUID.randomUUID(); var other=UUID.randomUUID();
        String key="soundconnect:push-device:mutation:"+owner;
        String otherKey="soundconnect:push-device:mutation:"+other;
        try(var pool=Executors.newFixedThreadPool(8)) {
            var start=new CountDownLatch(1); var futures=new ArrayList<Future<Boolean>>();
            for(int i=0;i<80;i++) {
                var limiter=i%2==0?first:second;
                futures.add(pool.submit(()->{
                    start.await();
                    try { limiter.check(owner); return true; }
                    catch(RateLimitedException throttled) {
                        assertThat(throttled.getRetryAfterSeconds()).isBetween(1L,60L); return false;
                    }
                }));
            }
            start.countDown(); int allowed=0;
            for(var result:futures) if(result.get(10,TimeUnit.SECONDS)) allowed++;
            assertThat(allowed).isEqualTo(60);
            assertThat(redis.opsForValue().get(key)).isEqualTo("80");
            assertThat(redis.getExpire(key)).isBetween(1L,60L);
            second.check(other); // Another authenticated account has its own bounded bucket.
            redis.expire(key,Duration.ofMillis(1));
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(Boolean.TRUE.equals(redis.hasKey(key)) && System.nanoTime()<until) Thread.sleep(5);
            assertThat(redis.hasKey(key)).isFalse(); first.check(owner);
            assertThat(redis.opsForValue().get(key)).isEqualTo("1");
        } finally { redis.delete(java.util.List.of(key,otherKey)); connection.destroy(); }
    }
}
