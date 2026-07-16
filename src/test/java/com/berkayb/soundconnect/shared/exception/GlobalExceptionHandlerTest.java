package com.berkayb.soundconnect.shared.exception;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

	private GlobalExceptionHandler handler;
	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		handler = new GlobalExceptionHandler();
		mockMvc = MockMvcBuilders.standaloneSetup(new FixtureController())
				.setControllerAdvice(handler)
				.build();
	}

	@Test
	void malformedJsonReturnsStableBadRequestWithoutParserDetails() throws Exception {
		mockMvc.perform(post("/fixture/payload")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\": \"top-secret\", \"broken\": }"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value(ErrorType.MALFORMED_REQUEST.getCode()))
				.andExpect(jsonPath("$.message").value(ErrorType.MALFORMED_REQUEST.getMessage()))
				.andExpect(jsonPath("$.details[0]").value(ErrorType.MALFORMED_REQUEST.getDetails()))
				.andExpect(jsonPath("$").value(org.hamcrest.Matchers.not(
						org.hamcrest.Matchers.hasToString(org.hamcrest.Matchers.containsString("top-secret")))));
	}

	@Test
	void dtoValidationReturnsFieldMessagesWithoutRejectedValues() throws Exception {
		mockMvc.perform(post("/fixture/payload")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\": \"\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value(ErrorType.VALIDATION_ERROR.getCode()))
				.andExpect(jsonPath("$.details[0]").value("name: must not be blank"));
	}

	@Test
	void requestParameterTypeMismatchReturnsBadRequest() throws Exception {
		mockMvc.perform(get("/fixture/number").param("value", "not-a-number"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value(ErrorType.TYPE_MISMATCH.getCode()))
				.andExpect(jsonPath("$.details[0]").value("Parameter 'value' has an invalid value."));
	}

	@Test
	void missingRequestParameterReturnsBadRequest() throws Exception {
		mockMvc.perform(get("/fixture/number"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value(ErrorType.MISSING_REQUEST_PARAMETER.getCode()))
				.andExpect(jsonPath("$.details[0]").value("Required parameter 'value' is missing."));
	}

	@Test
	void unknownEndpointReturnsNotFoundContract() {
		var request = new org.springframework.mock.web.MockHttpServletRequest("GET", "/fixture/missing");
		ResponseEntity<ErrorResponse> response = handler.handleNoResourceFound(
				new NoResourceFoundException(HttpMethod.GET, "/fixture/missing"),
				request
		);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().getCode()).isEqualTo(ErrorType.ENDPOINT_NOT_FOUND.getCode());
	}

	@Test
	void unsupportedHttpMethodReturnsMethodNotAllowedAndAllowHeader() throws Exception {
		mockMvc.perform(post("/fixture/number"))
				.andExpect(status().isMethodNotAllowed())
				.andExpect(header().string("Allow", containsString("GET")))
				.andExpect(jsonPath("$.code").value(ErrorType.METHOD_NOT_ALLOWED.getCode()));
	}

	@Test
	void unsupportedContentTypeReturnsUnsupportedMediaType() throws Exception {
		mockMvc.perform(post("/fixture/payload")
						.contentType(MediaType.TEXT_PLAIN)
						.content("name=not-json"))
				.andExpect(status().isUnsupportedMediaType())
				.andExpect(jsonPath("$.code").value(ErrorType.UNSUPPORTED_MEDIA_TYPE.getCode()));
	}

	@Test
	void constraintViolationReturnsSafeValidationDetails() {
		@SuppressWarnings("unchecked")
		ConstraintViolation<Object> violation = mock(ConstraintViolation.class);
		Path path = mock(Path.class);
		when(path.toString()).thenReturn("request.name");
		when(violation.getPropertyPath()).thenReturn(path);
		when(violation.getMessage()).thenReturn("must not be blank");

		ResponseEntity<ErrorResponse> response = handler.handleConstraintViolation(
				new ConstraintViolationException(Set.of(violation)),
				new org.springframework.mock.web.MockHttpServletRequest("POST", "/fixture/constraint")
		);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().getCode()).isEqualTo(ErrorType.CONSTRAINT_VIOLATION.getCode());
		assertThat(response.getBody().getDetails()).containsExactly("request.name: must not be blank");
	}

	@Test
	void dataIntegrityViolationReturnsConflictWithoutDatabaseDetails() {
		ResponseEntity<ErrorResponse> response = handler.handleDataIntegrityViolation(
				new DataIntegrityViolationException("duplicate secret@example.com in users_email_key"),
				new org.springframework.mock.web.MockHttpServletRequest("POST", "/fixture/payload")
		);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().getCode()).isEqualTo(ErrorType.DATA_INTEGRITY_CONFLICT.getCode());
		assertThat(response.getBody().toString()).doesNotContain("secret@example.com", "users_email_key");
	}

	@Test
	void serverSideDomainErrorDoesNotExposeCustomInternalDetails() {
		var request = new org.springframework.mock.web.MockHttpServletRequest("POST", "/fixture/internal");
		ResponseEntity<ErrorResponse> response = handler.handleSoundConnectException(
				new SoundConnectException(ErrorType.INTERNAL_ERROR, "broker nack: secret-routing-key"),
				request
		);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().getDetails()).containsExactly(ErrorType.INTERNAL_ERROR.getDetails());
		assertThat(response.getBody().toString()).doesNotContain("secret-routing-key");
	}

	@Test
	void clientDomainErrorDoesNotExposeArbitraryCustomDetails() {
		var request = new org.springframework.mock.web.MockHttpServletRequest("POST", "/fixture/login");
		ResponseEntity<ErrorResponse> response = handler.handleSoundConnectException(
				new SoundConnectException(
						ErrorType.UNAUTHORIZED,
						"provider response for secret@example.com: invalid-client-secret"
				),
				request
		);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().getDetails()).containsExactly(ErrorType.UNAUTHORIZED.getDetails());
		assertThat(response.getBody().toString())
				.doesNotContain("secret@example.com", "invalid-client-secret");
	}

	@Test
	void rateLimitedDomainErrorReturnsRetryAfterAndStableContract() {
		var request = new org.springframework.mock.web.MockHttpServletRequest("POST", "/fixture/media/init");

		ResponseEntity<ErrorResponse> response = handler.handleRateLimitedException(
				new RateLimitedException(ErrorType.MEDIA_UPLOAD_CONCURRENCY_LIMITED, 17L),
				request
		);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
		assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("17");
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().getCode())
				.isEqualTo(ErrorType.MEDIA_UPLOAD_CONCURRENCY_LIMITED.getCode());
	}

	@Test
	void authenticationAndAuthorizationUseDifferentStatuses() {
		var request = new org.springframework.mock.web.MockHttpServletRequest("GET", "/fixture/secure");

		ResponseEntity<ErrorResponse> unauthenticated = handler.handleAuthenticationFailure(
				new BadCredentialsException("raw password should not be exposed"), request);
		ResponseEntity<ErrorResponse> forbidden = handler.handleAccessDenied(
				new AccessDeniedException("internal policy name"), request);

		assertThat(unauthenticated.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(unauthenticated.getBody()).isNotNull();
		assertThat(unauthenticated.getBody().getCode()).isEqualTo(ErrorType.UNAUTHORIZED.getCode());
		assertThat(unauthenticated.getBody().toString()).doesNotContain("raw password");
		assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(forbidden.getBody()).isNotNull();
		assertThat(forbidden.getBody().getCode()).isEqualTo(ErrorType.FORBIDDEN_ACCESS.getCode());
		assertThat(forbidden.getBody().toString()).doesNotContain("internal policy name");
	}

	@RestController
	private static class FixtureController {

		@PostMapping("/fixture/payload")
		String payload(@Valid @RequestBody FixturePayload payload) {
			return payload.name();
		}

		@GetMapping("/fixture/number")
		Integer number(@RequestParam Integer value) {
			return value;
		}
	}

	private record FixturePayload(@NotBlank String name) {
	}
}
