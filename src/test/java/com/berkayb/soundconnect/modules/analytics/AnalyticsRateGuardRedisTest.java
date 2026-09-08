package com.berkayb.soundconnect.modules.analytics;

import com.berkayb.soundconnect.auth.ratelimit.TrustedProxyClientAddressResolver;
import com.berkayb.soundconnect.shared.exception.*;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Testcontainers
class AnalyticsRateGuardRedisTest {
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.2.5-alpine").withExposedPorts(6379).withReuse(false);
    static LettuceConnectionFactory factory; static StringRedisTemplate redis;
    AnalyticsProperties properties; AnalyticsIdentity identity; AnalyticsRateGuard guard;
    @BeforeAll static void connectDisposableOnly() {
        assertThat(REDIS.isRunning()).isTrue(); factory=new LettuceConnectionFactory(REDIS.getHost(),REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();factory.start();redis=new StringRedisTemplate(factory);redis.afterPropertiesSet();
    }
    @AfterAll static void cleanup() { if(factory!=null)factory.destroy(); }
    @BeforeEach void setup() {
        assertThat(factory.getHostName()).isEqualTo(REDIS.getHost());assertThat(factory.getPort()).isEqualTo(REDIS.getMappedPort(6379));
        redis.delete(List.of("soundconnect:{venue-analytics}:submit-global","soundconnect:{venue-analytics}:read-global"));
        properties=AnalyticsServiceTest.properties();identity=new AnalyticsIdentity(properties);
        guard=new AnalyticsRateGuard(redis,new TrustedProxyClientAddressResolver(List.of()),identity,properties);
    }
    @Test void concurrentActorBatchesConsumeObservationCostAtomicallyAndSetExpiry() throws Exception {
        UUID client=UUID.randomUUID();properties.setActorObservationsPerMinute(60);
        try(var executor=Executors.newFixedThreadPool(10)) {
            var start=new CountDownLatch(1);var futures=new ArrayList<Future<Boolean>>();
            for(int index=0;index<10;index++)futures.add(executor.submit(()->{start.await();try{guard.observations(null,client,20);return true;}catch(RateLimitedException limited){return false;}}));
            start.countDown();int accepted=0;for(var future:futures)if(future.get(10,TimeUnit.SECONDS))accepted++;
            assertThat(accepted).isEqualTo(3);
        }
        String key=key("actor",identity.actor(null,client));assertThat(redis.opsForValue().get(key)).isEqualTo("60");assertThat(redis.getExpire(key)).isBetween(1L,60L);
    }
    @Test void globalLimitRejectsBeforeCreatingNewIpKeysAndSpoofedForwardedHeaderIsIgnored() {
        properties.setGlobalRequestsPerMinute(2);guard.submission(request("203.0.113.1"));guard.submission(request("203.0.113.2"));
        var request=request("203.0.113.3");request.addHeader("X-Forwarded-For","192.0.2.50");
        assertThatThrownBy(()->guard.submission(request)).isInstanceOf(RateLimitedException.class);
        assertThat(redis.hasKey(key("submit-ip","203.0.113.3"))).isFalse();assertThat(redis.hasKey(key("submit-ip","192.0.2.50"))).isFalse();
    }
    @Test void exhaustedWindowKeepsExistingCountsAndLongestRetryWithoutRefresh() {
        String ip="203.0.113.4",global="soundconnect:{venue-analytics}:submit-global",local=key("submit-ip",ip);
        redis.opsForValue().set(local,"300",Duration.ofSeconds(12));redis.opsForValue().set(global,"3000",Duration.ofSeconds(45));
        var limited=catchThrowableOfType(()->guard.submission(request(ip)),RateLimitedException.class);
        assertThat(limited.getRetryAfterSeconds()).isBetween(40L,45L);assertThat(redis.opsForValue().get(local)).isEqualTo("300");assertThat(redis.getExpire(local)).isLessThanOrEqualTo(12);
    }
    @Test void readQuotaIsIndependentFromSubmissionAndFollowsAccountAcrossIps() {
        properties.setOwnerReadsPerMinute(2);UUID owner=UUID.randomUUID();
        guard.read(request("203.0.113.5"),owner);guard.read(request("203.0.113.6"),owner);
        assertThatThrownBy(()->guard.read(request("203.0.113.7"),owner)).isInstanceOf(RateLimitedException.class);
        guard.submission(request("203.0.113.7"));
    }
    @Test void redisFailureAndAmbiguousResponseFailClosedInsteadOfAcceptingUnmeteredWrites() {
        var broken=mock(StringRedisTemplate.class);var guarded=new AnalyticsRateGuard(broken,new TrustedProxyClientAddressResolver(List.of()),identity,properties);
        assertThatThrownBy(()->guarded.submission(request("203.0.113.8"))).isInstanceOf(ServiceUnavailableRetryException.class);
        when(broken.execute(any(org.springframework.data.redis.core.script.RedisScript.class),anyList(),any(Object[].class))).thenThrow(new IllegalStateException("private Redis info"));
        assertThatThrownBy(()->guarded.observations(null,UUID.randomUUID(),1)).isInstanceOf(ServiceUnavailableRetryException.class);
    }
    String key(String scope,String value){return "soundconnect:{venue-analytics}:"+scope+":"+identity.quotaKey(scope,value);}
    MockHttpServletRequest request(String ip){var request=new MockHttpServletRequest();request.setRemoteAddr(ip);return request;}
}
