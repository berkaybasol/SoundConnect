package com.berkayb.soundconnect.auth.controller;

import com.berkayb.soundconnect.auth.dto.request.LoginRequestDto;
import com.berkayb.soundconnect.auth.dto.response.LoginResponse;
import com.berkayb.soundconnect.auth.passwordreset.dto.request.ForgotPasswordRequestDto;
import com.berkayb.soundconnect.auth.passwordreset.dto.request.ResetPasswordRequestDto;
import com.berkayb.soundconnect.auth.passwordreset.service.PasswordResetService;
import com.berkayb.soundconnect.auth.service.AuthService;
import com.berkayb.soundconnect.shared.constant.EndPoints;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.GlobalExceptionHandler;
import com.berkayb.soundconnect.shared.exception.RateLimitedException;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AuthControllerPasswordValidationMvcTest {

	@Mock
	AuthService authService;

	@Mock
	PasswordResetService passwordResetService;

	private final ObjectMapper objectMapper = new ObjectMapper();
	private LocalValidatorFactoryBean validator;
	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		validator = new LocalValidatorFactoryBean();
		validator.afterPropertiesSet();
		mockMvc = MockMvcBuilders
				.standaloneSetup(new AuthControllerImpl(authService, passwordResetService))
				.setControllerAdvice(new GlobalExceptionHandler())
				.setValidator(validator)
				.build();
	}

	@AfterEach
	void closeValidator() {
		validator.close();
	}

	@Test
	void loginAcceptsLegacyPasswordThroughMvcValidation() throws Exception {
		LoginRequestDto request = new LoginRequestDto("listener", "old");
		when(authService.login(request)).thenReturn(successfulLogin());

		performLogin(request)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true));

		verify(authService).login(request);
	}

	@Test
	void loginAcceptsExactlySeventyTwoUtf8BytesThroughMvcValidation() throws Exception {
		LoginRequestDto request = new LoginRequestDto("listener", "ş".repeat(36));
		when(authService.login(request)).thenReturn(successfulLogin());

		performLogin(request).andExpect(status().isOk());

		verify(authService).login(request);
	}

	@Test
	void loginRejectsMoreThanSeventyTwoUtf8BytesBeforeCallingTheService() throws Exception {
		LoginRequestDto request = new LoginRequestDto("listener", "ş".repeat(37));

		performLogin(request)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value(4000))
				.andExpect(jsonPath("$.details[0]").value("password: Şifre en fazla 72 UTF-8 byte olabilir."));

		verifyNoInteractions(authService);
	}

	@Test
	void resetAcceptsPasswordPolicyBoundaryAndMatchingConfirmation() throws Exception {
		ResetPasswordRequestDto request = new ResetPasswordRequestDto(
				"user@example.com", "123456", "ş".repeat(36), "ş".repeat(36));
		when(passwordResetService.resetPassword(request))
				.thenReturn(BaseResponse.<Void>builder().success(true).code(200).build());

		performReset(request).andExpect(status().isOk());

		verify(passwordResetService).resetPassword(request);
	}

	@Test
	void resetRejectsMoreThanSeventyTwoUtf8BytesBeforeCallingTheService() throws Exception {
		ResetPasswordRequestDto request = new ResetPasswordRequestDto(
				"user@example.com", "123456", "ş".repeat(37), "ş".repeat(37));

		performReset(request)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value(4000))
				.andExpect(jsonPath("$.details[0]")
						.value("password: Şifre en fazla 72 UTF-8 byte olabilir."));

		verifyNoInteractions(passwordResetService);
	}

	@Test
	void resetRejectsMismatchedConfirmationAndMalformedCode() throws Exception {
		ResetPasswordRequestDto request = new ResetPasswordRequestDto(
				"user@example.com", "12ab", "new-password", "different-password");

		performReset(request)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value(4000))
				.andExpect(jsonPath("$.details").isArray());

		verifyNoInteractions(passwordResetService);
	}

	@Test
	void forgotPasswordAcceptsUsernameIdentifier() throws Exception {
		ForgotPasswordRequestDto request = new ForgotPasswordRequestDto("listener");
		when(passwordResetService.requestPasswordReset(request))
				.thenReturn(BaseResponse.<Void>builder().success(true).code(200).build());

		mockMvc.perform(post(EndPoints.Auth.BASE + EndPoints.Auth.FORGOT_PASSWORD)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsBytes(request)))
				.andExpect(status().isOk());

		verify(passwordResetService).requestPasswordReset(request);
	}

	@Test
	void forgotPasswordAcceptsLegacyEmailJsonAlias() throws Exception {
		ForgotPasswordRequestDto expected =
				new ForgotPasswordRequestDto("legacy@example.com");
		when(passwordResetService.requestPasswordReset(expected))
				.thenReturn(BaseResponse.<Void>builder().success(true).code(200).build());

		mockMvc.perform(post(EndPoints.Auth.BASE + EndPoints.Auth.FORGOT_PASSWORD)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"legacy@example.com"}
								"""))
				.andExpect(status().isOk());

		verify(passwordResetService).requestPasswordReset(expected);
	}

	@Test
	void resetPasswordAcceptsLegacyEmailJsonAlias() throws Exception {
		ResetPasswordRequestDto expected = new ResetPasswordRequestDto(
				"legacy@example.com", "123456", "new-password", "new-password");
		when(passwordResetService.resetPassword(expected))
				.thenReturn(BaseResponse.<Void>builder().success(true).code(200).build());

		mockMvc.perform(post(EndPoints.Auth.BASE + EndPoints.Auth.RESET_PASSWORD)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "email":"legacy@example.com",
								  "code":"123456",
								  "password":"new-password",
								  "rePassword":"new-password"
								}
								"""))
				.andExpect(status().isOk());

		verify(passwordResetService).resetPassword(expected);
	}

	@Test
	void forgotPasswordRejectsBlankIdentifierBeforeCallingTheService() throws Exception {
		mockMvc.perform(post(EndPoints.Auth.BASE + EndPoints.Auth.FORGOT_PASSWORD)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"identifier":"   "}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value(4000));

		verifyNoInteractions(passwordResetService);
	}

	@Test
	void forgotPasswordReturnsUsernameSpecificNotFoundContract() throws Exception {
		ForgotPasswordRequestDto request =
				new ForgotPasswordRequestDto("missing-user");
		when(passwordResetService.requestPasswordReset(request))
				.thenThrow(new SoundConnectException(
						ErrorType.PASSWORD_RESET_USERNAME_NOT_FOUND));

		mockMvc.perform(post(EndPoints.Auth.BASE + EndPoints.Auth.FORGOT_PASSWORD)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsBytes(request)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value(1109));
	}

	@Test
	void forgotPasswordReturnsEmailSpecificNotFoundContract() throws Exception {
		ForgotPasswordRequestDto request =
				new ForgotPasswordRequestDto("missing@example.com");
		when(passwordResetService.requestPasswordReset(request))
				.thenThrow(new SoundConnectException(
						ErrorType.PASSWORD_RESET_EMAIL_NOT_FOUND));

		mockMvc.perform(post(EndPoints.Auth.BASE + EndPoints.Auth.FORGOT_PASSWORD)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsBytes(request)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value(1108));
	}

	@Test
	void forgotPasswordReturnsUnsupportedProviderContract() throws Exception {
		ForgotPasswordRequestDto request =
				new ForgotPasswordRequestDto("google-user");
		when(passwordResetService.requestPasswordReset(request))
				.thenThrow(new SoundConnectException(
						ErrorType.PASSWORD_RESET_PROVIDER_UNSUPPORTED));

		mockMvc.perform(post(EndPoints.Auth.BASE + EndPoints.Auth.FORGOT_PASSWORD)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsBytes(request)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value(1110));
	}

	@Test
	void forgotPasswordReturnsSafeDeliveryFailureContract() throws Exception {
		ForgotPasswordRequestDto request =
				new ForgotPasswordRequestDto("listener");
		when(passwordResetService.requestPasswordReset(request))
				.thenThrow(new SoundConnectException(
						ErrorType.PASSWORD_RESET_DELIVERY_FAILED));

		mockMvc.perform(post(EndPoints.Auth.BASE + EndPoints.Auth.FORGOT_PASSWORD)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsBytes(request)))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.code").value(1111));
	}

	@Test
	void forgotPasswordCooldownReturnsHttp429AndRetryAfter() throws Exception {
		ForgotPasswordRequestDto request =
				new ForgotPasswordRequestDto("listener");
		when(passwordResetService.requestPasswordReset(request))
				.thenThrow(new RateLimitedException(
						ErrorType.AUTH_RATE_LIMITED, 29L));

		mockMvc.perform(post(EndPoints.Auth.BASE + EndPoints.Auth.FORGOT_PASSWORD)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsBytes(request)))
				.andExpect(status().isTooManyRequests())
				.andExpect(header().string("Retry-After", "29"))
				.andExpect(jsonPath("$.code").value(1104));
	}

	private org.springframework.test.web.servlet.ResultActions performLogin(LoginRequestDto request) throws Exception {
		return mockMvc.perform(post(EndPoints.Auth.BASE + EndPoints.Auth.LOGIN)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsBytes(request)));
	}

	private org.springframework.test.web.servlet.ResultActions performReset(
			ResetPasswordRequestDto request
	) throws Exception {
		return mockMvc.perform(post(EndPoints.Auth.BASE + EndPoints.Auth.RESET_PASSWORD)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsBytes(request)));
	}

	private static BaseResponse<LoginResponse> successfulLogin() {
		return BaseResponse.<LoginResponse>builder()
				.success(true)
				.code(200)
				.message("authenticated")
				.build();
	}
}
