package com.berkayb.soundconnect.auth.otp.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
class OtpServiceTest {

	@Mock
	RedisTemplate<String, String> redisTemplate;

	private OtpService otpService;
	private static final String IDENTITY_DIGEST =
			"b4c9a289323b21a01c3e940f150eb9b8c542587f1abfd8f0e1cc1ffc5e475514";

	@BeforeEach
	void setUp() {
		otpService = new OtpService(redisTemplate);
		ReflectionTestUtils.setField(otpService, "otpExpiredMinutes", 3L);
		ReflectionTestUtils.setField(otpService, "otpLength", 6);
		ReflectionTestUtils.setField(otpService, "maxAttempt", 5);
		ReflectionTestUtils.setField(otpService, "resendCoolDownSeconds", 30L);
	}

	@Test
	@SuppressWarnings({"rawtypes", "unchecked"})
	void initialOtpUsesThePrivateClusterSafeKeyNamespace() {
		doReturn(1L).when(redisTemplate)
				.execute(any(RedisScript.class), anyList(), any(Object[].class));

		OtpService.OtpIssueClaim claim = otpService.acquireInitialOtp("User@Example.com");

		assertThat(claim.acquired()).isTrue();
		ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
		verify(redisTemplate).execute(any(RedisScript.class), keys.capture(), any(Object[].class));
		assertThat(keys.getValue()).containsExactly(
				"soundconnect:otp:{" + IDENTITY_DIGEST + "}:code",
				"soundconnect:otp:{" + IDENTITY_DIGEST + "}:resend-guard"
		);
		assertThat(keys.getValue()).allSatisfy(key -> assertThat(key).doesNotContain("user@example.com"));
	}

	@Test
	@SuppressWarnings({"rawtypes", "unchecked"})
	void verifyOtpDelegatesConsumeAndAttemptMutationToOneAtomicScript() {
		doReturn(1L, 0L).when(redisTemplate)
				.execute(any(RedisScript.class), anyList(), any(Object[].class));

		assertThat(otpService.verifyOtp("User@Example.com", "123456")).isTrue();
		assertThat(otpService.verifyOtp("User@Example.com", "123456")).isFalse();

		ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
		ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
		verify(redisTemplate, times(2)).execute(
				any(RedisScript.class),
				keys.capture(),
				arguments.capture()
		);
		assertThat(keys.getAllValues()).allSatisfy(value ->
				assertThat(value).containsExactly(
						"soundconnect:otp:{" + IDENTITY_DIGEST + "}:code"
				)
		);
		assertThat(arguments.getAllValues()).allSatisfy(value ->
				assertThat(value).containsExactly("123456", "5")
		);
		verifyNoMoreInteractions(redisTemplate);
	}

	@Test
	@SuppressWarnings({"rawtypes", "unchecked"})
	void acquireResendOtpAtomicallyInstallsOtpAndCooldownForWinner() {
		doReturn(1L).when(redisTemplate)
				.execute(any(RedisScript.class), anyList(), any(Object[].class));

		OtpService.OtpIssueClaim claim = otpService.acquireResendOtp("user@example.com");

		assertThat(claim.acquired()).isTrue();
		assertThat(claim.code()).matches("\\d{6}");
		assertThat(claim.toString()).doesNotContain(claim.code());

		ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
		ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
		verify(redisTemplate).execute(any(RedisScript.class), keys.capture(), arguments.capture());
		assertThat(keys.getValue()).containsExactly(
				"soundconnect:otp:{" + IDENTITY_DIGEST + "}:code",
				"soundconnect:otp:{" + IDENTITY_DIGEST + "}:resend-guard"
		);
		assertThat(keys.getValue()).allSatisfy(key -> assertThat(key).doesNotContain("user@example.com"));
		assertThat(arguments.getValue()).containsExactly(
				claim.code(),
				"30000",
				"180000"
		);
		verifyNoMoreInteractions(redisTemplate);
	}

	@Test
	@SuppressWarnings("rawtypes")
	void acquireResendOtpReturnsAtomicRetryAfterForLoserWithoutExposingCode() {
		doReturn(-25_001L).when(redisTemplate)
				.execute(any(RedisScript.class), anyList(), any(Object[].class));

		OtpService.OtpIssueClaim claim = otpService.acquireResendOtp("user@example.com");

		assertThat(claim.acquired()).isFalse();
		assertThat(claim.code()).isNull();
		assertThat(claim.cooldownSeconds()).isEqualTo(26L);
	}

	@Test
	@SuppressWarnings({"rawtypes", "unchecked"})
	void decoyResendUsesAnIsolatedHashedNamespace() {
		doReturn(1L).when(redisTemplate)
				.execute(any(RedisScript.class), anyList(), any(Object[].class));

		OtpService.OtpIssueClaim claim = otpService.acquireDecoyResendOtp("User@Example.com");

		assertThat(claim.acquired()).isTrue();
		ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
		verify(redisTemplate).execute(any(RedisScript.class), keys.capture(), any(Object[].class));
		assertThat(keys.getValue()).containsExactly(
				"soundconnect:otp:{" + IDENTITY_DIGEST + "}:decoy-code",
				"soundconnect:otp:{" + IDENTITY_DIGEST + "}:decoy-resend-guard"
		);
		assertThat(keys.getValue()).allSatisfy(key -> assertThat(key).doesNotContain("user@example.com"));
	}
}
