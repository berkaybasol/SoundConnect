package com.berkayb.soundconnect.modules.comment.abuse;

import com.berkayb.soundconnect.modules.engagement.enums.EngagementTargetType;
import com.berkayb.soundconnect.shared.exception.*;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.transaction.support.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Runs the production Lua only against this disposable mapped Redis port; never loads app configuration. */
@Testcontainers @Timeout(30)
class CommentBurstGuardRedisTest {
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.2.5-alpine")
            .withExposedPorts(6379).withReuse(false);
    static LettuceConnectionFactory factory, secondFactory; static StringRedisTemplate redis, secondRedis;
    CommentBurstGuard guard, secondGuard; UUID actor,target; String key;
    static final EngagementTargetType TYPE=EngagementTargetType.MEDIA;

    @BeforeAll static void connect() {
        assertThat(REDIS.isRunning()).isTrue();
        factory=new LettuceConnectionFactory(REDIS.getHost(),REDIS.getMappedPort(6379)); factory.afterPropertiesSet(); factory.start();
        redis=new StringRedisTemplate(factory); redis.afterPropertiesSet();
        secondFactory=new LettuceConnectionFactory(REDIS.getHost(),REDIS.getMappedPort(6379));
        secondFactory.afterPropertiesSet(); secondFactory.start();
        secondRedis=new StringRedisTemplate(secondFactory); secondRedis.afterPropertiesSet();
    }
    @AfterAll static void disconnect() {
        if(secondFactory!=null) secondFactory.destroy();
        if(factory!=null) factory.destroy();
    }
    @BeforeEach void setup() {
        assertThat(factory.getHostName()).isEqualTo(REDIS.getHost());
        assertThat(factory.getPort()).isEqualTo(REDIS.getMappedPort(6379));
        assertThat(secondFactory.getHostName()).isEqualTo(REDIS.getHost());
        assertThat(secondFactory.getPort()).isEqualTo(REDIS.getMappedPort(6379));
        guard=new CommentBurstGuard(redis); secondGuard=new CommentBurstGuard(secondRedis);
        actor=UUID.randomUUID(); target=UUID.randomUUID(); key=CommentBurstGuard.key(actor,TYPE,target);
    }

    @Test void firstThreeAreAllowedAndRejectionsNeverExtendExpiryOrAddMembers() {
        for(int i=0;i<3;i++) accepted(actor,TYPE,target);
        var before=redis.getExpire(key,TimeUnit.MILLISECONDS);
        for(int i=0;i<8;i++) {
            var limited=catchThrowableOfType(() -> accepted(actor,TYPE,target),RateLimitedException.class);
            assertThat(limited.getRetryAfterSeconds()).isBetween(1L,30L);
            assertThat(redis.getExpire(key,TimeUnit.MILLISECONDS)).isPositive().isLessThanOrEqualTo(before);
        }
        assertThat(redis.opsForZSet().size(key)).isEqualTo(3);
    }

    @Test void targetUserAndTargetTypeAreIndependentWithoutAccountWidePenalty() {
        for(int i=0;i<3;i++) accepted(actor,TYPE,target);
        assertThatThrownBy(() -> accepted(actor,TYPE,target)).isInstanceOf(RateLimitedException.class);
        accepted(actor,TYPE,UUID.randomUUID());
        accepted(UUID.randomUUID(),TYPE,target);
        accepted(actor,EngagementTargetType.EVENT,target);
        assertThat(redis.opsForZSet().size(key)).isEqualTo(3);
    }

    @Test void rollingBoundaryPrunesOnlyExpiredSendsAndRetryTracksOldestNotNewest() {
        long now=serverNow();
        redis.opsForZSet().add(key,"oldest",now-12_000);
        redis.opsForZSet().add(key,"middle",now-8_000);
        redis.opsForZSet().add(key,"newest",now-2_000);
        redis.expire(key,Duration.ofSeconds(28));
        var limited=catchThrowableOfType(() -> accepted(actor,TYPE,target),RateLimitedException.class);
        assertThat(limited.getRetryAfterSeconds()).isBetween(1L,18L);
        // Advance only the oldest fixture score across the actual production 30s boundary; no 30s sleep.
        redis.opsForZSet().add(key,"oldest",serverNow()-30_000);
        accepted(actor,TYPE,target);
        assertThat(redis.opsForZSet().range(key,0,-1)).hasSize(3).contains("middle","newest").doesNotContain("oldest");
        assertThat(redis.getExpire(key,TimeUnit.MILLISECONDS)).isBetween(28_000L,30_000L);
    }

    @Test void allExpiredReservationsAllowFreshBurstAndKeyStillHasBoundedTtl() {
        long now=serverNow();
        for(int i=0;i<3;i++) redis.opsForZSet().add(key,"expired"+i,now-30_001-i);
        redis.expire(key,Duration.ofMillis(30_000));
        for(int i=0;i<3;i++) accepted(actor,TYPE,target);
        assertThat(redis.opsForZSet().range(key,0,-1)).hasSize(3).noneMatch(s -> s.startsWith("expired"));
        assertThat(redis.getExpire(key,TimeUnit.MILLISECONDS)).isPositive().isLessThanOrEqualTo(30_000);
    }

    @Test void concurrentRequestsAcrossTwoIndependentClientsCannotAdmitMoreThanThree() throws Exception {
        var start=new CountDownLatch(1); var results=new ArrayList<Future<Boolean>>();
        try(var executor=Executors.newFixedThreadPool(12)) {
            for(int i=0;i<30;i++) {
                CommentBurstGuard client=i%2==0 ? guard : secondGuard;
                results.add(executor.submit(() -> {
                    start.await();
                    try {
                        transaction(() -> client.reserve(actor,TYPE,target),TransactionSynchronization.STATUS_COMMITTED);
                        return true;
                    } catch(RateLimitedException limited) { return false; }
                }));
            }
            start.countDown(); int accepted=0;
            for(var result:results) if(result.get(15,TimeUnit.SECONDS)) accepted++;
            assertThat(accepted).isEqualTo(3);
        }
        assertThat(redis.opsForZSet().size(key)).isEqualTo(3);
    }

    @Test void confirmedRollbackReleasesOnlyOwnTokenAndCommittedOrUnknownReservationsRemain() {
        accepted(actor,TYPE,target);
        transaction(() -> guard.reserve(actor,TYPE,target),TransactionSynchronization.STATUS_UNKNOWN);
        var before=redis.opsForZSet().range(key,0,-1);
        transaction(() -> guard.reserve(actor,TYPE,target),TransactionSynchronization.STATUS_ROLLED_BACK);
        assertThat(redis.opsForZSet().range(key,0,-1)).containsExactlyInAnyOrderElementsOf(before);
        accepted(actor,TYPE,target);
        assertThatThrownBy(() -> accepted(actor,TYPE,target)).isInstanceOf(RateLimitedException.class);
    }

    @Test void oldRollbackCannotRemoveNewerReservationsAfterTheOldWindowExpires() {
        List<TransactionSynchronization> oldCallbacks=new ArrayList<>();
        transaction(() -> { guard.reserve(actor,TYPE,target); oldCallbacks.addAll(TransactionSynchronizationManager.getSynchronizations()); },TransactionSynchronization.STATUS_UNKNOWN);
        for(String member:redis.opsForZSet().range(key,0,-1)) redis.opsForZSet().add(key,member,serverNow()-30_001);
        for(int i=0;i<3;i++) accepted(actor,TYPE,target);
        var before=redis.opsForZSet().range(key,0,-1);
        oldCallbacks.forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        assertThat(redis.opsForZSet().range(key,0,-1)).containsExactlyInAnyOrderElementsOf(before);
    }

    @Test void actualRedisCommandFailureFailsClosedWithoutResettingExistingTtl() {
        redis.opsForValue().set(key,"wrong-type-fixture",Duration.ofSeconds(30));
        long before=redis.getExpire(key,TimeUnit.MILLISECONDS);
        assertThatThrownBy(() -> accepted(actor,TYPE,target)).isInstanceOfSatisfying(ServiceUnavailableRetryException.class,e -> {
            assertThat(e.getErrorType()).isEqualTo(ErrorType.COMMENT_BURST_UNAVAILABLE);
            assertThat(e.getRetryAfterSeconds()).isEqualTo(5);
        });
        assertThat(redis.opsForValue().get(key)).isEqualTo("wrong-type-fixture");
        assertThat(redis.getExpire(key,TimeUnit.MILLISECONDS)).isPositive().isLessThanOrEqualTo(before);
    }

    @Test void lostResponseAfterActualRedisReservationIsCleanedOnConfirmedRollback() {
        StringRedisTemplate lostResponse=spy(redis);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            assertThat(redis.opsForZSet().size(key)).isEqualTo(1);
            throw new RedisConnectionFailureException("simulated lost response after actual script execution");
        }).when(lostResponse).execute(any(RedisScript.class),anyList(),any(Object[].class));
        var uncertainGuard=new CommentBurstGuard(lostResponse);
        assertThatThrownBy(() -> transaction(() -> uncertainGuard.reserve(actor,TYPE,target),TransactionSynchronization.STATUS_COMMITTED))
                .isInstanceOf(ServiceUnavailableRetryException.class);
        assertThat(redis.hasKey(key)).isFalse();
        accepted(actor,TYPE,target);
        assertThat(redis.opsForZSet().size(key)).isEqualTo(1);
    }

    private void accepted(UUID actor,EngagementTargetType type,UUID target) {
        transaction(() -> guard.reserve(actor,type,target),TransactionSynchronization.STATUS_COMMITTED);
    }
    private void transaction(Runnable action,int successStatus) {
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
        TransactionSynchronizationManager.initSynchronization(); TransactionSynchronizationManager.setActualTransactionActive(true);
        int status=TransactionSynchronization.STATUS_ROLLED_BACK;
        try { action.run(); status=successStatus; }
        finally {
            int completed=status;
            TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCompletion(completed));
            TransactionSynchronizationManager.clearSynchronization(); TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }
    private long serverNow() {
        return redis.execute(new DefaultRedisScript<>("local t=redis.call('TIME'); return tonumber(t[1])*1000+math.floor(tonumber(t[2])/1000)",Long.class),List.of());
    }
}
