package com.berkayb.soundconnect.modules.message.dm.abuse;

import com.berkayb.soundconnect.shared.exception.RateLimitedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** Only the explicitly supplied disposable loopback Redis; every key belongs to a random fixture UUID. */
@EnabledIfSystemProperty(named="push.test.redis-url", matches="redis://127\\.0\\.0\\.1:[0-9]+")
class DmRateLimitRedisTest {
    @Test void bothSharedBucketsAreAtomicAndBlockedPeerDoesNotDrainOtherPeerCapacity() {
        withRedis((redis, sender, keys) -> {
            var properties = new DmRateLimitProperties(); properties.setGlobalLimit(5); properties.setRecipientLimit(2);
            var guard = new DmRateLimitGuard(redis, properties);
            UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
            addKeys(keys, sender, a, b, c);
            guard.check(sender, a); guard.check(sender, a);
            for (int i=0; i<10; i++) assertThatThrownBy(() -> guard.check(sender,a)).isInstanceOf(RateLimitedException.class);
            guard.check(sender,b); guard.check(sender,b); guard.check(sender,c);
            assertThatThrownBy(() -> guard.check(sender,c)).isInstanceOf(RateLimitedException.class);
            assertThat(redis.opsForValue().get(keys.getFirst())).isEqualTo("5");
            assertThat(redis.opsForValue().get(keys.get(1))).isEqualTo("2");
        });
    }

    @Test void concurrentNodesCannotOverrunTheLimitAndExpiryRestoresCapacity() {
        withRedis((redis,sender,keys) -> {
            var properties = new DmRateLimitProperties(); properties.setGlobalLimit(30); properties.setRecipientLimit(30);
            var first = new DmRateLimitGuard(redis,properties); var second = new DmRateLimitGuard(redis,properties);
            UUID recipient = UUID.randomUUID(); addKeys(keys,sender,recipient);
            try (var pool = Executors.newFixedThreadPool(8)) {
                var start = new CountDownLatch(1); var futures = new ArrayList<Future<Boolean>>();
                for(int i=0; i<50; i++) {
                    var guard = i%2==0?first:second;
                    futures.add(pool.submit(() -> {
                        start.await();
                        try { guard.check(sender,recipient); return true; }
                        catch(RateLimitedException limited) { assertThat(limited.getRetryAfterSeconds()).isBetween(1L,60L); return false; }
                    }));
                }
                start.countDown(); int allowed=0;
                for(var future:futures) if(future.get(10,TimeUnit.SECONDS)) allowed++;
                assertThat(allowed).isEqualTo(30);
                for(var key:keys) { assertThat(redis.opsForValue().get(key)).isEqualTo("30"); redis.expire(key,Duration.ofMillis(1)); }
                long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
                while(keys.stream().anyMatch(key -> Boolean.TRUE.equals(redis.hasKey(key))) && System.nanoTime()<until) Thread.sleep(5);
                first.check(sender,recipient);
                assertThat(redis.opsForValue().get(keys.getFirst())).isEqualTo("1");
            }
        });
    }

    private void withRedis(Work work) {
        URI uri = URI.create(System.getProperty("push.test.redis-url"));
        var connection = new LettuceConnectionFactory(uri.getHost(),uri.getPort()); connection.afterPropertiesSet();
        var redis = new StringRedisTemplate(connection); var keys = new ArrayList<String>();
        try { work.run(redis,UUID.randomUUID(),keys); }
        catch(Exception failure) { throw new AssertionError(failure); }
        finally { if(!keys.isEmpty()) redis.delete(keys); connection.destroy(); }
    }
    private void addKeys(List<String> keys, UUID sender, UUID... recipients) {
        String base="soundconnect:dm:send:{"+sender+"}:"; keys.add(base+"all");
        for(var recipient:recipients) keys.add(base+"recipient:"+recipient);
    }
    @FunctionalInterface interface Work { void run(StringRedisTemplate redis,UUID sender,List<String> keys) throws Exception; }
}
