package com.berkayb.soundconnect.auth.security;

import com.berkayb.soundconnect.auth.service.CustomUserDetailsService;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.AuthProvider;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.security.SecurityErrorResponseWriter;
import com.berkayb.soundconnect.shared.util.JwtUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterSecurityTest {

	@Mock JwtTokenProvider jwtTokenProvider;
	@Mock CustomUserDetailsService userDetailsService;
	@Mock JwtUtil jwtUtil;
	@Mock ListenerProfileChoiceGate listenerProfileChoiceGate;
	@Mock SecurityErrorResponseWriter securityErrorResponseWriter;
	@Mock HttpServletRequest request;
	@Mock HttpServletResponse response;
	@Mock FilterChain filterChain;
	@InjectMocks JwtAuthenticationFilter filter;

	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void rolelessGoogleOnboardingTokenOnlyAuthenticatesCompletionRequest() throws Exception {
		UUID userId = UUID.randomUUID();
		UserDetailsImpl principal = rolelessGooglePrincipal(userId);
		stubValidToken(userId, principal);
		when(request.getMethod()).thenReturn("POST");
		when(request.getRequestURI()).thenReturn("/api/v1/auth/complete-google-profile");

		filter.doFilterInternal(request, response, filterChain);

		verify(filterChain).doFilter(request, response);
		assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
				.isSameAs(principal);
	}

	@Test
	void rolelessGoogleOnboardingTokenDelegatesOtherRoutesAsUnauthenticated() throws Exception {
		UUID userId = UUID.randomUUID();
		stubValidToken(userId, rolelessGooglePrincipal(userId));
		when(request.getMethod()).thenReturn("GET");

		filter.doFilterInternal(request, response, filterChain);

		verify(filterChain).doFilter(request, response);
		verify(response, never()).sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid or expired token");
		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
	}

	@Test
	void pendingListenerGateWritesStableErrorAndStopsTheChain() throws Exception {
		UUID userId = UUID.randomUUID();
		UserDetailsImpl principal = new UserDetailsImpl(User.builder()
				.id(userId)
				.username("listener")
				.email("listener@example.com")
				.password("encoded")
				.status(UserStatus.ACTIVE)
				.emailVerified(true)
				.roles(Set.of(Role.builder().name("ROLE_LISTENER").build()))
				.build());
		stubValidToken(userId, principal);
		when(listenerProfileChoiceGate.shouldReject(eq(request), any())).thenReturn(true);

		filter.doFilterInternal(request, response, filterChain);

		verify(securityErrorResponseWriter).write(
				request, response, ErrorType.LISTENER_PROFILE_CHOICE_REQUIRED);
		verifyNoInteractions(filterChain);
	}

	@Test
	void invalidTokenClearsContextAndLetsSecurityChainChooseTheResponse() throws Exception {
		when(jwtUtil.getTokenFromRequest(request)).thenReturn("invalid-token");
		when(jwtTokenProvider.validateToken("invalid-token")).thenReturn(false);

		filter.doFilterInternal(request, response, filterChain);

		verify(filterChain).doFilter(request, response);
		verifyNoInteractions(userDetailsService);
		verify(response, never()).sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid or expired token");
		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
	}

	@Test
	void deletedTokenSubjectIsTreatedAsAnonymous() throws Exception {
		UUID userId = UUID.randomUUID();
		when(jwtUtil.getTokenFromRequest(request)).thenReturn("valid-token");
		when(jwtTokenProvider.validateToken("valid-token")).thenReturn(true);
		when(jwtTokenProvider.getUserIdFromToken("valid-token")).thenReturn(userId);
		when(userDetailsService.loadUserById(userId))
				.thenThrow(new SoundConnectException(ErrorType.USER_NOT_FOUND));

		filter.doFilterInternal(request, response, filterChain);

		verify(filterChain).doFilter(request, response);
		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
	}

	@Test
	void malformedTokenSubjectIsTreatedAsAnonymous() throws Exception {
		when(jwtUtil.getTokenFromRequest(request)).thenReturn("valid-signature-invalid-subject");
		when(jwtTokenProvider.validateToken("valid-signature-invalid-subject")).thenReturn(true);
		when(jwtTokenProvider.getUserIdFromToken("valid-signature-invalid-subject"))
				.thenThrow(new IllegalArgumentException("invalid UUID subject"));

		filter.doFilterInternal(request, response, filterChain);

		verify(filterChain).doFilter(request, response);
		verifyNoInteractions(userDetailsService);
		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
	}

	@Test
	void databaseFailureIsNotDisguisedAsAnAuthenticationFailure() {
		UUID userId = UUID.randomUUID();
		when(jwtUtil.getTokenFromRequest(request)).thenReturn("valid-token");
		when(jwtTokenProvider.validateToken("valid-token")).thenReturn(true);
		when(jwtTokenProvider.getUserIdFromToken("valid-token")).thenReturn(userId);
		when(userDetailsService.loadUserById(userId))
				.thenThrow(new DataAccessResourceFailureException("database unavailable"));

		assertThatThrownBy(() -> filter.doFilterInternal(request, response, filterChain))
				.isInstanceOf(DataAccessResourceFailureException.class);

		verifyNoInteractions(filterChain);
		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
	}

	@Test
	void nonNotFoundDomainFailureIsNotDisguisedAsAnAuthenticationFailure() {
		UUID userId = UUID.randomUUID();
		when(jwtUtil.getTokenFromRequest(request)).thenReturn("valid-token");
		when(jwtTokenProvider.validateToken("valid-token")).thenReturn(true);
		when(jwtTokenProvider.getUserIdFromToken("valid-token")).thenReturn(userId);
		when(userDetailsService.loadUserById(userId))
				.thenThrow(new SoundConnectException(ErrorType.INTERNAL_ERROR));

		assertThatThrownBy(() -> filter.doFilterInternal(request, response, filterChain))
				.isInstanceOf(SoundConnectException.class)
				.extracting("errorType")
				.isEqualTo(ErrorType.INTERNAL_ERROR);

		verifyNoInteractions(filterChain);
	}

	@ParameterizedTest
	@ValueSource(strings = {"/api/v1/public/search/profiles", "/api/v1/public/media/owner/BAND/id",
			"/api/v1/profiles/MUSICIAN/id/media", "/api/v1/public/studio-profiles/id/rooms",
			"/api/v1/public/studio-profiles/id/equipment", "/api/v1/public/bands/id",
			"/api/v1/promotions/displayable/VENUE_MANAGEMENT_PANEL"})
	void invalidOrExpiredBearerOnAudienceSourceReturnsAuthContractAndNeverReadsGuestData(String path) throws Exception {
		audienceRequest(path);
		when(jwtUtil.getTokenFromRequest(request)).thenReturn("expired-token");
		when(jwtTokenProvider.validateToken("expired-token")).thenReturn(false);
		filter.doFilterInternal(request, response, filterChain);
		verify(securityErrorResponseWriter).write(request, response, ErrorType.UNAUTHORIZED);
		verifyNoInteractions(filterChain, userDetailsService);
		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
	}

	@ParameterizedTest
	@ValueSource(strings = {"DELETED", "MISSING", "INACTIVE", "ROLELESS"})
	void revokedOrUnusableAudienceSessionCannotDowngradeToGuest(String failure) throws Exception {
		audienceRequest("/api/v1/public/search/profiles");
		UUID userId = UUID.randomUUID();
		when(jwtUtil.getTokenFromRequest(request)).thenReturn("token");
		when(jwtTokenProvider.validateToken("token")).thenReturn(true);
		when(jwtTokenProvider.getUserIdFromToken("token")).thenReturn(userId);
		switch (failure) {
			case "DELETED" -> when(userDetailsService.loadUserById(userId))
					.thenThrow(new SoundConnectException(ErrorType.USER_NOT_FOUND));
			case "MISSING" -> when(userDetailsService.loadUserById(userId))
					.thenThrow(new org.springframework.security.core.userdetails.UsernameNotFoundException("removed"));
			case "INACTIVE" -> {
				var principal = rolelessGooglePrincipal(userId);
				principal.getUser().setStatus(UserStatus.INACTIVE);
				when(userDetailsService.loadUserById(userId)).thenReturn(principal);
			}
			default -> when(userDetailsService.loadUserById(userId)).thenReturn(rolelessGooglePrincipal(userId));
		}
		filter.doFilterInternal(request, response, filterChain);
		verify(securityErrorResponseWriter).write(request, response, ErrorType.UNAUTHORIZED);
		verifyNoInteractions(filterChain);
		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
	}

	@Test void malformedAudienceTokenUsesAuthContractInsteadOfGuestProjection() throws Exception {
		audienceRequest("/api/v1/public/search/profiles");
		when(jwtUtil.getTokenFromRequest(request)).thenReturn("malformed");
		when(jwtTokenProvider.validateToken("malformed")).thenThrow(new io.jsonwebtoken.MalformedJwtException("invalid"));
		filter.doFilterInternal(request, response, filterChain);
		verify(securityErrorResponseWriter).write(request, response, ErrorType.UNAUTHORIZED);
		verifyNoInteractions(filterChain, userDetailsService);
	}

	@Test void emptyBearerOnAudienceSourceCannotBecomeGuest() throws Exception {
		audienceRequest("/api/v1/public/search/profiles");
		when(request.getHeader("Authorization")).thenReturn("Bearer ");
		filter.doFilterInternal(request, response, filterChain);
		verify(securityErrorResponseWriter).write(request, response, ErrorType.UNAUTHORIZED);
		verifyNoInteractions(filterChain, jwtTokenProvider, userDetailsService);
	}

	@Test void headerlessAudienceSourceRemainsAGuestRead() throws Exception {
		when(request.getMethod()).thenReturn("GET");
		when(request.getRequestURI()).thenReturn("/api/v1/public/search/profiles");
		filter.doFilterInternal(request, response, filterChain);
		verify(filterChain).doFilter(request, response);
		verifyNoInteractions(securityErrorResponseWriter, userDetailsService, jwtTokenProvider);
	}

	@ParameterizedTest
	@ValueSource(strings = {"/api/v1/events/id", "/api/v1/cities", "/api/v1/public/unrelated"})
	void unrelatedPublicReadsKeepTheirExistingInvalidTokenBehavior(String path) throws Exception {
		when(request.getMethod()).thenReturn("GET");
		when(request.getRequestURI()).thenReturn(path);
		when(jwtUtil.getTokenFromRequest(request)).thenReturn("invalid");
		when(jwtTokenProvider.validateToken("invalid")).thenReturn(false);
		filter.doFilterInternal(request, response, filterChain);
		verify(filterChain).doFilter(request, response);
		verifyNoInteractions(securityErrorResponseWriter, userDetailsService);
	}

	@Test void rejectedAudienceBearerReturnsTheExisting401JsonContractWithoutAControllerRead() throws Exception {
		var actualRequest = new org.springframework.mock.web.MockHttpServletRequest("GET", "/api/v1/public/search/profiles");
		actualRequest.addHeader("Authorization", "Bearer expired");
		var actualResponse = new org.springframework.mock.web.MockHttpServletResponse();
		var objectMapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
		var actualFilter = new JwtAuthenticationFilter(jwtTokenProvider, userDetailsService, new JwtUtil(jwtTokenProvider),
				listenerProfileChoiceGate, new SecurityErrorResponseWriter(objectMapper));
		when(jwtTokenProvider.validateToken("expired")).thenReturn(false);
		actualFilter.doFilterInternal(actualRequest, actualResponse, filterChain);
		assertThat(actualResponse.getStatus()).isEqualTo(401);
		assertThat(actualResponse.getHeader("Cache-Control")).isEqualTo("no-store");
		var body = objectMapper.readTree(actualResponse.getContentAsString());
		assertThat(body.path("code").asInt()).isEqualTo(ErrorType.UNAUTHORIZED.getCode());
		assertThat(body.path("path").asText()).isEqualTo("/api/v1/public/search/profiles");
		verifyNoInteractions(filterChain, userDetailsService);
	}

	@Test void validListenerAudienceRequestRetainsItsPrincipalForDownstreamFilters() throws Exception {
		audienceRequest("/api/v1/public/search/profiles");
		var principal = rolelessGooglePrincipal(UUID.randomUUID());
		principal.getUser().setRoles(Set.of(Role.builder().name("ROLE_LISTENER").build()));
		stubValidToken(principal.getId(), principal);
		filter.doFilterInternal(request, response, filterChain);
		verify(filterChain).doFilter(request, response);
		verifyNoInteractions(securityErrorResponseWriter);
		assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isSameAs(principal);
	}

	@Test void audienceDatabaseFailureStillPropagatesAsInfrastructureFailure() throws Exception {
		audienceRequest("/api/v1/public/search/profiles");
		UUID userId = UUID.randomUUID();
		when(jwtUtil.getTokenFromRequest(request)).thenReturn("token");
		when(jwtTokenProvider.validateToken("token")).thenReturn(true);
		when(jwtTokenProvider.getUserIdFromToken("token")).thenReturn(userId);
		when(userDetailsService.loadUserById(userId)).thenThrow(new DataAccessResourceFailureException("database down"));
		assertThatThrownBy(() -> filter.doFilterInternal(request, response, filterChain))
				.isInstanceOf(DataAccessResourceFailureException.class);
		verifyNoInteractions(filterChain, securityErrorResponseWriter);
	}

	private void audienceRequest(String path) {
		when(request.getMethod()).thenReturn("GET");
		when(request.getRequestURI()).thenReturn(path);
		when(request.getHeader("Authorization")).thenReturn("Bearer supplied-token");
	}

	private void stubValidToken(UUID userId, UserDetailsImpl principal) {
		when(jwtUtil.getTokenFromRequest(request)).thenReturn("valid-token");
		when(jwtTokenProvider.validateToken("valid-token")).thenReturn(true);
		when(jwtTokenProvider.getUserIdFromToken("valid-token")).thenReturn(userId);
		when(userDetailsService.loadUserById(userId)).thenReturn(principal);
	}

	private UserDetailsImpl rolelessGooglePrincipal(UUID userId) {
		return new UserDetailsImpl(User.builder()
				.id(userId)
				.username("google@example.com")
				.email("google@example.com")
				.password("random-bcrypt")
				.provider(AuthProvider.GOOGLE)
				.status(UserStatus.ACTIVE)
				.emailVerified(true)
				.roles(new HashSet<>())
				.build());
	}
}
