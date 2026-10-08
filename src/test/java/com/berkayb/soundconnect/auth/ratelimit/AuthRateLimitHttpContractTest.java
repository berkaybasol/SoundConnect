package com.berkayb.soundconnect.auth.ratelimit;

import com.berkayb.soundconnect.shared.exception.GlobalExceptionHandler;
import com.berkayb.soundconnect.shared.security.SecurityErrorResponseWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Servlet/MVC contracts with real guards; this is not a live HTTP acceptance. */
class AuthRateLimitHttpContractTest {

	private StringRedisTemplate redis;
	private MockMvc mvc;
	private LoginFixture fixture;

	@BeforeEach
	void setUp() {
		redis = mock(StringRedisTemplate.class);
		var properties = new AuthRateLimitProperties();
		var limiter = new AuthRateLimiter(redis, properties);
		var mapper = new ObjectMapper().findAndRegisterModules();
		var filter = new AuthRateLimitFilter(limiter, properties,
				new SecurityErrorResponseWriter(mapper), new TrustedProxyClientAddressResolver(List.of()));
		fixture = new LoginFixture(new AuthAccountRateLimitGuard(limiter, properties));
		mvc = MockMvcBuilders.standaloneSetup(fixture)
				.setControllerAdvice(new GlobalExceptionHandler()).addFilters(filter).build();
	}

	@ParameterizedTest
	@ValueSource(strings = {"login", "google-sign-in", "register", "verify-code", "resend-code",
			"username-availability", "password-reset/account", "forgot-password", "reset-password"})
	@SuppressWarnings("rawtypes")
	void redisOutageBlocksEveryProtectedPostBeforeControllerExecution(String endpoint) throws Exception {
		doThrow(new RedisConnectionFailureException("redis://secret-user:secret-pass@private-host"))
				.when(redis).execute(any(RedisScript.class), anyList(), any(Object[].class));

		mvc.perform(post("/api/v1/auth/" + endpoint).contentType(MediaType.APPLICATION_JSON)
				.content("\"private@example.test\""))
				.andExpect(status().isServiceUnavailable())
				.andExpect(header().string("Retry-After", "5"))
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.code").value(1115))
				.andExpect(content().string(not(containsString("secret"))))
				.andExpect(content().string(not(containsString("private"))));
		assertThat(fixture.accepted.get()).isZero();
	}

	@Test
	@SuppressWarnings("rawtypes")
	void accountGuardOutageAfterSuccessfulIpCheckReturnsTheSame503AndRecovers() throws Exception {
		doReturn(List.of(1L, 60L)).doThrow(new RedisConnectionFailureException("private failure"))
				.doReturn(List.of(2L, 60L)).doReturn(List.of(1L, 60L))
				.when(redis).execute(any(RedisScript.class), anyList(), any(Object[].class));

		mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("\"private-user\""))
				.andExpect(status().isServiceUnavailable())
				.andExpect(header().string("Retry-After", "5"))
				.andExpect(jsonPath("$.code").value(1115))
				.andExpect(content().string(not(containsString("private"))));
		assertThat(fixture.accepted.get()).isZero();

		mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("\"private-user\""))
				.andExpect(status().isOk()).andExpect(content().string("accepted"));
		assertThat(fixture.accepted.get()).isEqualTo(1);
	}

	@Test
	@SuppressWarnings("rawtypes")
	void healthyButExhaustedAccountQuotaRemains429() throws Exception {
		doReturn(List.of(1L, 60L)).doReturn(List.of(11L, 47L))
				.when(redis).execute(any(RedisScript.class), anyList(), any(Object[].class));

		mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("\"private-user\""))
				.andExpect(status().isTooManyRequests())
				.andExpect(header().string("Retry-After", "47"))
				.andExpect(jsonPath("$.code").value(1104));
		assertThat(fixture.accepted.get()).isZero();
	}

	@RestController
	static class LoginFixture {
		private final AuthAccountRateLimitGuard guard;
		private final AtomicInteger accepted = new AtomicInteger();

		LoginFixture(AuthAccountRateLimitGuard guard) {
			this.guard = guard;
		}

		@PostMapping("/api/v1/auth/login")
		String login(@RequestBody String username) {
			guard.checkLogin(username);
			accepted.incrementAndGet();
			return "accepted";
		}
	}
}
