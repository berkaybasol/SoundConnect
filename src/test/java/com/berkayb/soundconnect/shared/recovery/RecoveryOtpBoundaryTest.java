package com.berkayb.soundconnect.shared.recovery;

import com.berkayb.soundconnect.auth.otp.service.OtpService;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

@IsolatedRecoveryTest
class RecoveryOtpBoundaryTest {
    @BeforeAll static void hold() { RecoveryRuntime.quarantine(); }

    static Stream<Consumer<OtpService>> mutationPaths() {
        String email="recovery-owned@example.invalid";
        String generation=UUID.randomUUID().toString();
        return Stream.of(s->s.acquireInitialOtp(email),s->s.acquireResendOtp(email),
            s->s.acquireDecoyResendOtp(email),s->s.acquirePasswordResetOtp(email),
            s->s.verifyOtp(email,"123456"),s->s.verifyPasswordResetOtp(email,"123456"),
            s->s.cancelPasswordResetOtpIssue(email,generation),
            s->s.authorizePasswordResetMail(email,generation,Long.MAX_VALUE));
    }

    @ParameterizedTest @MethodSource("mutationPaths")
    @SuppressWarnings("unchecked")
    void directOtpEntryCannotConsumeClaimOrAuthorizeDuringHold(Consumer<OtpService> path) {
        RedisTemplate<String,String> redis=mock(RedisTemplate.class);
        var service=new OtpService(redis);
        ReflectionTestUtils.setField(service,"otpExpiredMinutes",3L);
        ReflectionTestUtils.setField(service,"otpLength",6);
        ReflectionTestUtils.setField(service,"maxAttempt",5);
        ReflectionTestUtils.setField(service,"resendCoolDownSeconds",30L);
        assertThatThrownBy(()->path.accept(service)).isInstanceOf(RecoveryRuntime.Held.class);
        verifyNoInteractions(redis);
    }
}
