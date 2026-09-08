package com.berkayb.soundconnect.modules.comment.abuse;

import com.berkayb.soundconnect.modules.engagement.enums.EngagementTargetType;
import com.berkayb.soundconnect.shared.exception.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.transaction.support.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CommentBurstGuardTest {
    @Mock StringRedisTemplate redis;
    @Mock ZSetOperations<String,String> sortedSets;
    @InjectMocks CommentBurstGuard guard;
    final UUID actor=UUID.randomUUID(), target=UUID.randomUUID();
    final EngagementTargetType type=EngagementTargetType.EVENT;

    @BeforeEach void begin() {
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }
    @AfterEach void clear() {
        if(TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    }

    @Test void missingOrReadOnlyTransactionFailsClosedBeforeRedis() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        assertUnavailable(() -> guard.reserve(actor,type,target));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        assertUnavailable(() -> guard.reserve(actor,type,target));
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        TransactionSynchronizationManager.clearSynchronization();
        assertUnavailable(() -> guard.reserve(actor,type,target));
        verifyNoInteractions(redis);
    }

    @Test void missingIdentifiersNeverCreateGlobalOrNullAccountCounters() {
        assertUnavailable(() -> guard.reserve(null,type,target));
        assertUnavailable(() -> guard.reserve(actor,null,target));
        assertUnavailable(() -> guard.reserve(actor,type,null));
        verifyNoInteractions(redis);
    }

    @Test void knownRateLimitKeepsDistinct429AndCeilingRetry() {
        when(redis.execute(any(RedisScript.class),anyList(),any(Object[].class))).thenReturn(17L);
        assertThatThrownBy(() -> guard.reserve(actor,type,target)).isInstanceOfSatisfying(RateLimitedException.class,e -> {
            assertThat(e.getErrorType()).isEqualTo(ErrorType.COMMENT_BURST_RATE_LIMITED);
            assertThat(e.getRetryAfterSeconds()).isEqualTo(17);
        });
        complete(TransactionSynchronization.STATUS_ROLLED_BACK);
        verify(redis,never()).opsForZSet();
    }

    @Test void missingAndInvalidRedisResultsFailClosed() {
        when(redis.execute(any(RedisScript.class),anyList(),any(Object[].class))).thenReturn(null,-1L,31L);
        for(int i=0;i<3;i++) assertUnavailable(() -> guard.reserve(actor,type,target));
    }

    @Test void commitAndUnknownOutcomeNeverReleaseReservationEvenIfCallerLaterFails() {
        when(redis.execute(any(RedisScript.class),anyList(),any(Object[].class))).thenReturn(0L);
        guard.reserve(actor,type,target);
        complete(TransactionSynchronization.STATUS_COMMITTED);
        complete(TransactionSynchronization.STATUS_UNKNOWN);
        verify(redis,never()).opsForZSet();
        verify(redis,never()).delete(anyString());
    }

    @Test void rollbackRemovesExactlyOwnUniqueTokenAndNeverDeletesTargetCounter() {
        List<String> tokens=new ArrayList<>();
        when(redis.execute(any(RedisScript.class),anyList(),any(Object[].class))).thenAnswer(i -> {
            assertThat(i.getArgument(0,RedisScript.class).getScriptAsString()).contains("redis.call('TIME')");
            assertThat(i.getArgument(1,List.class)).containsExactly(CommentBurstGuard.key(actor,type,target));
            assertThat(i.getArgument(2,String.class)).isEqualTo("30000");
            assertThat(i.getArgument(3,String.class)).isEqualTo("3");
            tokens.add(i.getArgument(4)); return 0L;
        });
        when(redis.opsForZSet()).thenReturn(sortedSets);
        guard.reserve(actor,type,target); guard.reserve(actor,type,target);
        assertThat(tokens).hasSize(2).doesNotHaveDuplicates();
        complete(TransactionSynchronization.STATUS_ROLLED_BACK);
        for(String token:tokens) verify(sortedSets).remove(CommentBurstGuard.key(actor,type,target),token);
        verify(redis,never()).delete(anyString());
    }

    @Test void ambiguousReserveFailureStillRegistersRollbackCleanupBeforeRedisCall() {
        var token=new AtomicReference<String>();
        when(redis.execute(any(RedisScript.class),anyList(),any(Object[].class))).thenAnswer(i -> {
            assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
            token.set(i.getArgument(4)); throw new RedisConnectionFailureException("response lost after execution");
        });
        when(redis.opsForZSet()).thenReturn(sortedSets);
        assertUnavailable(() -> guard.reserve(actor,type,target));
        complete(TransactionSynchronization.STATUS_ROLLED_BACK);
        verify(sortedSets).remove(CommentBurstGuard.key(actor,type,target),token.get());
    }

    @Test void rollbackRedisFailureCannotReplaceOriginalDatabaseFailure() {
        when(redis.execute(any(RedisScript.class),anyList(),any(Object[].class))).thenReturn(0L);
        when(redis.opsForZSet()).thenReturn(sortedSets);
        when(sortedSets.remove(anyString(),any(Object[].class))).thenThrow(new RedisConnectionFailureException("offline"));
        guard.reserve(actor,type,target);
        assertThatCode(() -> complete(TransactionSynchronization.STATUS_ROLLED_BACK)).doesNotThrowAnyException();
    }

    private void complete(int outcome) { TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCompletion(outcome)); }
    private void assertUnavailable(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ServiceUnavailableRetryException.class,e -> {
            assertThat(e.getErrorType()).isEqualTo(ErrorType.COMMENT_BURST_UNAVAILABLE);
            assertThat(e.getRetryAfterSeconds()).isEqualTo(5);
        });
    }
}
