package com.berkayb.soundconnect.auth.controller;

import com.berkayb.soundconnect.auth.dto.request.LoginRequestDto;
import com.berkayb.soundconnect.auth.dto.response.LoginResponse;
import com.berkayb.soundconnect.auth.service.AuthService;
import com.berkayb.soundconnect.shared.constant.EndPoints;
import com.berkayb.soundconnect.shared.exception.GlobalExceptionHandler;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AuthControllerPasswordValidationMvcTest {

	@Mock
	AuthService authService;

	private final ObjectMapper objectMapper = new ObjectMapper();
	private LocalValidatorFactoryBean validator;
	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		validator = new LocalValidatorFactoryBean();
		validator.afterPropertiesSet();
		mockMvc = MockMvcBuilders
				.standaloneSetup(new AuthControllerImpl(authService))
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

	private org.springframework.test.web.servlet.ResultActions performLogin(LoginRequestDto request) throws Exception {
		return mockMvc.perform(post(EndPoints.Auth.BASE + EndPoints.Auth.LOGIN)
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
