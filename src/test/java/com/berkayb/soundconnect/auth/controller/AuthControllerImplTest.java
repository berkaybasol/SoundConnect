package com.berkayb.soundconnect.auth.controller;

import com.berkayb.soundconnect.auth.dto.request.LoginRequestDto;
import com.berkayb.soundconnect.auth.dto.response.LoginResponse;
import com.berkayb.soundconnect.auth.otp.dto.request.VerifyCodeRequestDto;
import com.berkayb.soundconnect.auth.otp.dto.request.ResendCodeRequestDto;
import com.berkayb.soundconnect.auth.otp.dto.response.ResendCodeResponseDto;
import com.berkayb.soundconnect.auth.passwordreset.dto.request.ForgotPasswordRequestDto;
import com.berkayb.soundconnect.auth.passwordreset.dto.request.ResetPasswordRequestDto;
import com.berkayb.soundconnect.auth.passwordreset.service.PasswordResetService;
import com.berkayb.soundconnect.auth.service.AuthService;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthControllerImplTest {

	@Mock
	AuthService authService;

	@Mock
	PasswordResetService passwordResetService;

	@InjectMocks
	AuthControllerImpl controller;

	@Test
	void loginForwardsBcryptMaximumPasswordWithoutChangingIt() {
		LoginRequestDto request = new LoginRequestDto("listener", "p".repeat(72));
		BaseResponse<LoginResponse> serviceResponse = BaseResponse.<LoginResponse>builder()
				.success(true)
				.code(200)
				.message("authenticated")
				.build();
		when(authService.login(request)).thenReturn(serviceResponse);

		ResponseEntity<BaseResponse<LoginResponse>> response = controller.login(request);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isSameAs(serviceResponse);
		verify(authService).login(request);
	}

	@Test
	void verifyEmailForwardsListenerSessionWithoutChangingTheResponse() {
		VerifyCodeRequestDto request = new VerifyCodeRequestDto("user@example.com", "123456");
		LoginResponse session = new LoginResponse(
				"listener-token",
				UserStatus.ACTIVE,
				UUID.randomUUID(),
				"listener",
				Set.of("ROLE_LISTENER"),
				Set.of(),
				false,
				true
		);
		BaseResponse<LoginResponse> serviceResponse = BaseResponse.<LoginResponse>builder()
				.success(true)
				.code(200)
				.message("verified")
				.data(session)
				.build();
		when(authService.verifyCode(request)).thenReturn(serviceResponse);

		ResponseEntity<BaseResponse<LoginResponse>> response = controller.verifyEmail(request);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isSameAs(serviceResponse);
		assertThat(response.getBody().getData()).isSameAs(session);
		verify(authService).verifyCode(request);
	}

	@Test
	void resendCooldownUsesHttp429AndRetryAfterHeader() {
		ResendCodeRequestDto request = new ResendCodeRequestDto("user@example.com");
		BaseResponse<ResendCodeResponseDto> serviceResponse = BaseResponse.<ResendCodeResponseDto>builder()
				.success(false)
				.code(429)
				.message("wait")
				.data(new ResendCodeResponseDto(120L, false, 17L))
				.build();
		when(authService.resendCode(request)).thenReturn(serviceResponse);

		ResponseEntity<BaseResponse<ResendCodeResponseDto>> response = controller.resendCode(request);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
		assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("17");
		assertThat(response.getBody()).isSameAs(serviceResponse);
	}

	@Test
	void acquiredResendKeepsTheEstablishedHttp200Contract() {
		ResendCodeRequestDto request = new ResendCodeRequestDto("user@example.com");
		BaseResponse<ResendCodeResponseDto> serviceResponse = BaseResponse.<ResendCodeResponseDto>builder()
				.success(true)
				.code(200)
				.message("sent")
				.data(new ResendCodeResponseDto(180L, true, 30L))
				.build();
		when(authService.resendCode(request)).thenReturn(serviceResponse);

		ResponseEntity<BaseResponse<ResendCodeResponseDto>> response = controller.resendCode(request);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getHeaders().containsKey(HttpHeaders.RETRY_AFTER)).isFalse();
		assertThat(response.getBody()).isSameAs(serviceResponse);
	}

	@Test
	void forgotPasswordReturnsExplicitKnownAccountSuccessContract() {
		ForgotPasswordRequestDto request =
				new ForgotPasswordRequestDto("user@example.com");
		BaseResponse<Void> serviceResponse = BaseResponse.<Void>builder()
				.success(true)
				.code(200)
				.message("Şifre sıfırlama kodu e-posta adresinize gönderildi.")
				.build();
		when(passwordResetService.requestPasswordReset(request)).thenReturn(serviceResponse);

		ResponseEntity<BaseResponse<Void>> response = controller.forgotPassword(request);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isSameAs(serviceResponse);
		verify(passwordResetService).requestPasswordReset(request);
	}

	@Test
	void resetPasswordForwardsValidatedRequest() {
		ResetPasswordRequestDto request = new ResetPasswordRequestDto(
				"user@example.com", "123456", "new-password", "new-password");
		BaseResponse<Void> serviceResponse = BaseResponse.<Void>builder()
				.success(true)
				.code(200)
				.message("updated")
				.build();
		when(passwordResetService.resetPassword(request)).thenReturn(serviceResponse);

		ResponseEntity<BaseResponse<Void>> response = controller.resetPassword(request);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isSameAs(serviceResponse);
		verify(passwordResetService).resetPassword(request);
	}
}
