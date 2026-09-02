package com.berkayb.soundconnect.shared.config;

import com.berkayb.soundconnect.auth.controller.AuthControllerImpl;
import com.berkayb.soundconnect.auth.passwordreset.service.PasswordResetService;
import com.berkayb.soundconnect.auth.ratelimit.AuthRateLimitFilter;
import com.berkayb.soundconnect.auth.security.JwtAuthenticationFilter;
import com.berkayb.soundconnect.auth.service.AuthService;
import com.berkayb.soundconnect.modules.tablegroup.controller.TableGroupController;
import com.berkayb.soundconnect.modules.tablegroup.service.TableGroupService;
import com.berkayb.soundconnect.modules.user.controller.user.UserAccountController;
import com.berkayb.soundconnect.modules.user.service.UserService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import com.berkayb.soundconnect.shared.security.RestAccessDeniedHandler;
import com.berkayb.soundconnect.shared.security.RestAuthenticationEntryPoint;
import com.berkayb.soundconnect.shared.security.SecurityErrorResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {
		AuthControllerImpl.class,
		UserAccountController.class,
		TableGroupController.class
})
@Import({
		SecurityConfig.class,
		RestAuthenticationEntryPoint.class,
		RestAccessDeniedHandler.class,
		SecurityErrorResponseWriter.class
})
class SecurityConfigAuthorizationTest {

	@Autowired MockMvc mockMvc;

	@MockitoBean AuthService authService;
	@MockitoBean PasswordResetService passwordResetService;
	@MockitoBean UserService userService;
	@MockitoBean TableGroupService tableGroupService;
	@MockitoBean JwtAuthenticationFilter jwtAuthenticationFilter;
	@MockitoBean AuthRateLimitFilter authRateLimitFilter;

	@BeforeEach
	void passThroughApplicationFilters() throws Exception {
		doAnswer(invocation -> {
			FilterChain chain = invocation.getArgument(2);
			chain.doFilter(invocation.getArgument(0), invocation.getArgument(1));
			return null;
		}).when(jwtAuthenticationFilter).doFilter(
				any(ServletRequest.class),
				any(ServletResponse.class),
				any(FilterChain.class)
		);
		doAnswer(invocation -> {
			FilterChain chain = invocation.getArgument(2);
			chain.doFilter(invocation.getArgument(0), invocation.getArgument(1));
			return null;
		}).when(authRateLimitFilter).doFilter(
				any(ServletRequest.class),
				any(ServletResponse.class),
				any(FilterChain.class)
		);
	}

	@Test
	void anonymousUsernameChangeIsRejectedByTheFilterChain() throws Exception {
		mockMvc.perform(patch("/api/v1/users/me/username")
						.contentType("application/json")
						.content("""
								{"username":"newname"}
								"""))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void anonymousTableGroupVenueOptionsAreRejectedByTheFilterChain() throws Exception {
		mockMvc.perform(get("/api/v1/table-groups/venue-options")
						.param("q", "Sound"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void forgotAndResetPasswordRemainPublicPostEndpoints() throws Exception {
		when(passwordResetService.requestPasswordReset(any())).thenReturn(okResponse());
		when(passwordResetService.resetPassword(any())).thenReturn(okResponse());

		mockMvc.perform(post("/api/v1/auth/forgot-password")
						.contentType("application/json")
						.content("""
								{"email":"user@example.com"}
								"""))
				.andExpect(status().isOk());
		mockMvc.perform(post("/api/v1/auth/reset-password")
						.contentType("application/json")
						.content("""
								{
								  "email":"user@example.com",
								  "code":"123456",
								  "password":"new-password",
								  "rePassword":"new-password"
								}
								"""))
				.andExpect(status().isOk());

		verify(passwordResetService).requestPasswordReset(any());
		verify(passwordResetService).resetPassword(any());
	}

	@Test
	void registrationAndPasswordResetPreflightEndpointsRemainPublic() throws Exception {
		mockMvc.perform(post("/api/v1/auth/username-availability")
						.contentType("application/json")
						.content("""
								{"username":"candidate"}
								"""))
				.andExpect(status().isOk());
		mockMvc.perform(post("/api/v1/auth/password-reset/account")
						.contentType("application/json")
						.content("""
								{"identifier":"candidate"}
								"""))
				.andExpect(status().isOk());

		verify(authService).usernameAvailability(any());
		verify(passwordResetService).resolveAccount(any());
	}

	@Test
	void unlistedFutureAuthEndpointDefaultsToAuthenticated() throws Exception {
		mockMvc.perform(post("/api/v1/auth/future-route"))
				.andExpect(status().isUnauthorized());
	}

	private BaseResponse<Void> okResponse() {
		return BaseResponse.<Void>builder()
				.success(true)
				.code(200)
				.message("ok")
				.build();
	}
}
