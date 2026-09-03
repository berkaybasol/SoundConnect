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
