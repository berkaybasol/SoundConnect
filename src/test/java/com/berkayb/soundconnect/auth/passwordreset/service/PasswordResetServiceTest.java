package com.berkayb.soundconnect.auth.passwordreset.service;

import com.berkayb.soundconnect.auth.otp.service.OtpService;
import com.berkayb.soundconnect.auth.passwordreset.dto.request.ForgotPasswordRequestDto;
import com.berkayb.soundconnect.auth.passwordreset.dto.request.ResetPasswordRequestDto;
import com.berkayb.soundconnect.auth.passwordreset.dto.response.PasswordResetAccountResponseDto;
import com.berkayb.soundconnect.auth.ratelimit.AuthAccountRateLimitGuard;
import com.berkayb.soundconnect.modules.profile.shared.resolver.dto.UserProfileTargetDto;
import com.berkayb.soundconnect.modules.profile.shared.resolver.dto.UserProfilesResolveResponseDto;
import com.berkayb.soundconnect.modules.profile.shared.resolver.service.PublicProfileResolverService;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.AuthProvider;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.RateLimitedException;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {

	private static final UUID USER_ID =
			UUID.fromString("73edbdbc-95f8-4f25-9625-c637b5ec824f");
	private static final String RATE_LIMIT_KEY = "user-id:" + USER_ID;
	private static final String SUCCESS_MESSAGE =
			"Şifre sıfırlama kodu e-posta adresinize gönderildi.";

	@Mock UserRepository userRepository;
	@Mock OtpService otpService;
	@Mock PasswordResetMailService mailService;
	@Mock PasswordEncoder passwordEncoder;
	@Mock AuthAccountRateLimitGuard accountRateLimitGuard;
	@Mock PublicProfileResolverService publicProfileResolverService;

	@InjectMocks PasswordResetService service;

	@Test
	void accountLookupReturnsCanonicalUsernameAndPublicProfilePictureWithoutIssuingOtp() {
		User user = localUser();
		when(userRepository.findByUsername("berkay")).thenReturn(Optional.of(user));
		when(publicProfileResolverService.resolveByUserId(USER_ID))
				.thenReturn(new UserProfilesResolveResponseDto(
						USER_ID,
						List.of(new UserProfileTargetDto(
								"LISTENER",
								UUID.randomUUID(),
								"Berkay",
								"https://cdn.example/avatar.jpg"))));

		BaseResponse<PasswordResetAccountResponseDto> response = service.resolveAccount(
				new ForgotPasswordRequestDto(" BeRKay "));

		assertThat(response.getSuccess()).isTrue();
		assertThat(response.getData().username()).isEqualTo("berkay");
		assertThat(response.getData().profilePictureUrl())
				.isEqualTo("https://cdn.example/avatar.jpg");
		verify(accountRateLimitGuard).checkPasswordResetLookup(RATE_LIMIT_KEY);
		verifyNoInteractions(otpService, mailService);
	}

	@Test
	void missingAccountLookupFailsBeforeConfirmationAndNeverIssuesOtp() {
		when(userRepository.findByUsername("missing-user")).thenReturn(Optional.empty());

		SoundConnectException exception = catchThrowableOfType(
				() -> service.resolveAccount(
						new ForgotPasswordRequestDto("missing-user")),
				SoundConnectException.class);

		assertThat(exception.getErrorType())
				.isEqualTo(ErrorType.PASSWORD_RESET_USERNAME_NOT_FOUND);
		verify(accountRateLimitGuard).checkPasswordResetLookup(
				"identifier:username:missing-user");
		verifyNoInteractions(otpService, mailService, publicProfileResolverService);
	}

	@Test
	void emailIdentifierQueuesRealOtpAndUsesStableAccountRateLimitKey() {
		User user = localUser();
		when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
		when(otpService.acquirePasswordResetOtp("user@example.com"))
				.thenReturn(acquired("123456"));

		BaseResponse<Void> response = service.requestPasswordReset(
				new ForgotPasswordRequestDto(" User@Example.COM "));

		assertSuccess(response);
		verify(accountRateLimitGuard).checkPasswordResetRequest(RATE_LIMIT_KEY);
		verify(otpService).acquirePasswordResetOtp("user@example.com");
		verify(mailService).queueResetCode("user@example.com", "123456");
	}

	@Test
	void usernameIdentifierUsesCanonicalUsernameAndDeliversToAccountEmail() {
		User user = localUser();
		when(userRepository.findByUsername("berkay")).thenReturn(Optional.of(user));
		when(otpService.acquirePasswordResetOtp("user@example.com"))
				.thenReturn(acquired("654321"));

		BaseResponse<Void> response = service.requestPasswordReset(
				new ForgotPasswordRequestDto("\uFEFFBeRKay\u2003"));

		assertSuccess(response);
		verify(userRepository).findByUsername("berkay");
		verify(accountRateLimitGuard).checkPasswordResetRequest(RATE_LIMIT_KEY);
		verify(mailService).queueResetCode("user@example.com", "654321");
	}

	@Test
	void existingUsernameContainingAtSignRemainsRecoverableWhenNoEmailMatches() {
		User user = localUser();
		user.setUsername("user@example.com");
		when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.empty());
		when(userRepository.findByUsername("user@example.com")).thenReturn(Optional.of(user));
		when(otpService.acquirePasswordResetOtp("user@example.com"))
				.thenReturn(acquired("111222"));

		assertSuccess(service.requestPasswordReset(
				new ForgotPasswordRequestDto("USER@EXAMPLE.COM")));

		verify(mailService).queueResetCode("user@example.com", "111222");
	}

	@Test
	void missingEmailReturnsEmailSpecificNotFoundWithoutAdvancingFlow() {
		when(userRepository.findByEmail("missing@example.com")).thenReturn(Optional.empty());
		when(userRepository.findByUsername("missing@example.com")).thenReturn(Optional.empty());

		SoundConnectException exception = catchThrowableOfType(
				() -> service.requestPasswordReset(
						new ForgotPasswordRequestDto("missing@example.com")),
				SoundConnectException.class);

		assertThat(exception.getErrorType())
				.isEqualTo(ErrorType.PASSWORD_RESET_EMAIL_NOT_FOUND);
		verify(accountRateLimitGuard).checkPasswordResetRequest(
				"identifier:email:missing@example.com");
		verifyNoInteractions(otpService, mailService);
	}

	@Test
	void missingUsernameReturnsUsernameSpecificNotFoundWithoutAdvancingFlow() {
		when(userRepository.findByUsername("missing-user")).thenReturn(Optional.empty());

		SoundConnectException exception = catchThrowableOfType(
				() -> service.requestPasswordReset(
						new ForgotPasswordRequestDto(" Missing-User ")),
				SoundConnectException.class);

		assertThat(exception.getErrorType())
				.isEqualTo(ErrorType.PASSWORD_RESET_USERNAME_NOT_FOUND);
		verify(accountRateLimitGuard).checkPasswordResetRequest(
				"identifier:username:missing-user");
		verifyNoInteractions(otpService, mailService);
	}

	@Test
	void externalProviderReturnsExplicitUnsupportedErrorWithoutOtpOrMail() {
		User googleUser = localUser();
		googleUser.setProvider(AuthProvider.GOOGLE);
		when(userRepository.findByUsername("berkay")).thenReturn(Optional.of(googleUser));

		SoundConnectException exception = catchThrowableOfType(
				() -> service.requestPasswordReset(
						new ForgotPasswordRequestDto("berkay")),
				SoundConnectException.class);

		assertThat(exception.getErrorType())
				.isEqualTo(ErrorType.PASSWORD_RESET_PROVIDER_UNSUPPORTED);
		verify(accountRateLimitGuard).checkPasswordResetRequest(RATE_LIMIT_KEY);
		verifyNoInteractions(otpService, mailService);
	}

	@Test
	void synchronousMailQueueFailureReturnsSafeDeliveryError() {
		User user = localUser();
		when(userRepository.findByEmail("user@example.com"))
				.thenReturn(Optional.of(user));
		when(otpService.acquirePasswordResetOtp("user@example.com"))
				.thenReturn(acquired("123456"));
		doThrow(new IllegalStateException("broker unavailable"))
				.when(mailService).queueResetCode("user@example.com", "123456");
		when(otpService.cancelPasswordResetOtpIssue("user@example.com", "123456"))
				.thenReturn(true);

		SoundConnectException exception = catchThrowableOfType(
				() -> service.requestPasswordReset(
						new ForgotPasswordRequestDto("user@example.com")),
				SoundConnectException.class);

		assertThat(exception.getErrorType())
				.isEqualTo(ErrorType.PASSWORD_RESET_DELIVERY_FAILED);
		verify(otpService).cancelPasswordResetOtpIssue(
				"user@example.com", "123456");
	}

	@Test
	void cancellationFailureDoesNotReplaceTheSafeDeliveryError() {
		User user = localUser();
		when(userRepository.findByEmail("user@example.com"))
				.thenReturn(Optional.of(user));
		when(otpService.acquirePasswordResetOtp("user@example.com"))
				.thenReturn(acquired("123456"));
		doThrow(new IllegalStateException("executor saturated"))
				.when(mailService).queueResetCode("user@example.com", "123456");
		when(otpService.cancelPasswordResetOtpIssue("user@example.com", "123456"))
				.thenThrow(new IllegalStateException("redis unavailable"));

		SoundConnectException exception = catchThrowableOfType(
				() -> service.requestPasswordReset(
						new ForgotPasswordRequestDto("user@example.com")),
				SoundConnectException.class);

		assertThat(exception.getErrorType())
				.isEqualTo(ErrorType.PASSWORD_RESET_DELIVERY_FAILED);
	}

	@Test
	void otpCooldownReturnsRateLimitInsteadOfClaimingThatMailWasSent() {
		User user = localUser();
		when(userRepository.findByEmail("user@example.com"))
				.thenReturn(Optional.of(user));
		when(otpService.acquirePasswordResetOtp("user@example.com"))
				.thenReturn(rejected(29L));

		RateLimitedException exception = catchThrowableOfType(
				() -> service.requestPasswordReset(
						new ForgotPasswordRequestDto("user@example.com")),
				RateLimitedException.class);

		assertThat(exception.getErrorType()).isEqualTo(ErrorType.AUTH_RATE_LIMITED);
		assertThat(exception.getRetryAfterSeconds()).isEqualTo(29L);
		verifyNoInteractions(mailService);
	}

	@Test
	void validUsernameAndCodeLockByUuidAndStoreBcryptHash() {
		User user = localUser();
		ResetPasswordRequestDto request = resetRequest(" BeRKay ");
		when(userRepository.findByUsername("berkay")).thenReturn(Optional.of(user));
		when(otpService.verifyPasswordResetOtp("user@example.com", "123456"))
				.thenReturn(true);
		when(userRepository.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(user));
		when(passwordEncoder.encode("new-password")).thenReturn("$2a$encoded");

		BaseResponse<Void> response = service.resetPassword(request);

		assertThat(response.getSuccess()).isTrue();
		assertThat(response.getCode()).isEqualTo(200);
		assertThat(user.getPassword()).isEqualTo("$2a$encoded");
		verify(accountRateLimitGuard).checkPasswordResetConfirm(RATE_LIMIT_KEY);
		verify(userRepository).findByIdForUpdate(USER_ID);
		verify(userRepository).save(user);
	}

	@Test
	void validEmailAndCodeUseTheSameAccountAndOtpIdentity() {
		User user = localUser();
		ResetPasswordRequestDto request = resetRequest(" User@Example.COM ");
		when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
		when(otpService.verifyPasswordResetOtp("user@example.com", "123456"))
				.thenReturn(true);
		when(userRepository.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(user));
		when(passwordEncoder.encode("new-password")).thenReturn("$2a$encoded");

		service.resetPassword(request);

		verify(accountRateLimitGuard).checkPasswordResetConfirm(RATE_LIMIT_KEY);
		verify(otpService).verifyPasswordResetOtp("user@example.com", "123456");
		verify(userRepository).findByIdForUpdate(USER_ID);
	}

	@Test
	void invalidOrExpiredCodeNeverLocksOrMutatesAccount() {
		User user = localUser();
		ResetPasswordRequestDto request = resetRequest("berkay");
		when(userRepository.findByUsername("berkay")).thenReturn(Optional.of(user));
		when(otpService.verifyPasswordResetOtp("user@example.com", "123456"))
				.thenReturn(false);

		SoundConnectException exception = catchThrowableOfType(
				() -> service.resetPassword(request),
				SoundConnectException.class);

		assertThat(exception.getErrorType())
				.isEqualTo(ErrorType.PASSWORD_RESET_CODE_INVALID);
		verify(userRepository, never()).findByIdForUpdate(USER_ID);
		verifyNoInteractions(passwordEncoder);
	}

	@Test
	void missingResetIdentifierReturnsTypedNotFoundBeforeOtpVerification() {
		ResetPasswordRequestDto request = resetRequest("missing-user");
		when(userRepository.findByUsername("missing-user")).thenReturn(Optional.empty());

		SoundConnectException exception = catchThrowableOfType(
				() -> service.resetPassword(request),
				SoundConnectException.class);

		assertThat(exception.getErrorType())
				.isEqualTo(ErrorType.PASSWORD_RESET_USERNAME_NOT_FOUND);
		verify(accountRateLimitGuard).checkPasswordResetConfirm(
				"identifier:username:missing-user");
		verifyNoInteractions(otpService, passwordEncoder);
	}

	@Test
	void usernameChangeBetweenLookupAndLockFailsClosedAfterOtpConsumption() {
		User lookupUser = localUser();
		User lockedUser = localUser();
		lockedUser.setUsername("renamed");
		ResetPasswordRequestDto request = resetRequest("berkay");
		when(userRepository.findByUsername("berkay")).thenReturn(Optional.of(lookupUser));
		when(otpService.verifyPasswordResetOtp("user@example.com", "123456"))
				.thenReturn(true);
		when(userRepository.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(lockedUser));

		SoundConnectException exception = catchThrowableOfType(
				() -> service.resetPassword(request),
				SoundConnectException.class);

		assertThat(exception.getErrorType())
				.isEqualTo(ErrorType.PASSWORD_RESET_USERNAME_NOT_FOUND);
		verifyNoInteractions(passwordEncoder);
	}

	@Test
	void providerChangeBetweenLookupAndLockFailsClosedAfterOtpConsumption() {
		User lookupUser = localUser();
		User lockedUser = localUser();
		lockedUser.setProvider(AuthProvider.GOOGLE);
		ResetPasswordRequestDto request = resetRequest("berkay");
		when(userRepository.findByUsername("berkay")).thenReturn(Optional.of(lookupUser));
		when(otpService.verifyPasswordResetOtp("user@example.com", "123456"))
				.thenReturn(true);
		when(userRepository.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(lockedUser));

		SoundConnectException exception = catchThrowableOfType(
				() -> service.resetPassword(request),
				SoundConnectException.class);

		assertThat(exception.getErrorType())
				.isEqualTo(ErrorType.PASSWORD_RESET_PROVIDER_UNSUPPORTED);
		verifyNoInteractions(passwordEncoder);
	}

	@Test
	void passwordUpdateFailureOccursAfterOtpConsumptionAndDoesNotRestoreCode() {
		ResetPasswordRequestDto request = resetRequest("berkay");
		User user = localUser();
		when(userRepository.findByUsername("berkay")).thenReturn(Optional.of(user));
		when(otpService.verifyPasswordResetOtp("user@example.com", "123456"))
				.thenReturn(true);
		when(userRepository.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(user));
		when(passwordEncoder.encode("new-password"))
				.thenThrow(new IllegalStateException("encoder unavailable"));

		IllegalStateException exception = catchThrowableOfType(
				() -> service.resetPassword(request),
				IllegalStateException.class);

		assertThat(exception).hasMessage("encoder unavailable");
		InOrder order = inOrder(otpService, userRepository, passwordEncoder);
		order.verify(otpService).verifyPasswordResetOtp("user@example.com", "123456");
		order.verify(userRepository).findByIdForUpdate(USER_ID);
		order.verify(passwordEncoder).encode("new-password");
		verify(otpService, never()).acquirePasswordResetOtp("user@example.com");
	}

	private static User localUser() {
		return User.builder()
				.id(USER_ID)
				.username("berkay")
				.email("user@example.com")
				.password("old-hash")
				.provider(AuthProvider.LOCAL)
				.build();
	}

	private static ResetPasswordRequestDto resetRequest(String identifier) {
		return new ResetPasswordRequestDto(
				identifier,
				"123456",
				"new-password",
				"new-password");
	}

	private static OtpService.OtpIssueClaim acquired(String code) {
		return new OtpService.OtpIssueClaim(true, code, 0L);
	}

	private static OtpService.OtpIssueClaim rejected(long cooldownSeconds) {
		return new OtpService.OtpIssueClaim(false, null, cooldownSeconds);
	}

	private static void assertSuccess(BaseResponse<Void> response) {
		assertThat(response.getSuccess()).isTrue();
		assertThat(response.getCode()).isEqualTo(200);
		assertThat(response.getMessage()).isEqualTo(SUCCESS_MESSAGE);
		assertThat(response.getData()).isNull();
	}
}
