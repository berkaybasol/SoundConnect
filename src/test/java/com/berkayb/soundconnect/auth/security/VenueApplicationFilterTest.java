package com.berkayb.soundconnect.auth.security;

import com.berkayb.soundconnect.auth.service.CustomUserDetailsService;
import com.berkayb.soundconnect.modules.application.venueapplication.service.VenueApplicationSessionAccess;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.security.SecurityErrorResponseWriter;
import com.berkayb.soundconnect.shared.util.JwtUtil;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class VenueApplicationFilterTest {
    private final JwtTokenProvider tokens = VenueApplicationTokenTest.provider();
    private final CustomUserDetailsService users = mock(CustomUserDetailsService.class);
    private final VenueApplicationSessionAccess access = mock(VenueApplicationSessionAccess.class);
    private final ListenerProfileChoiceGate listenerGate = mock(ListenerProfileChoiceGate.class);
    private final SecurityErrorResponseWriter errors = mock(SecurityErrorResponseWriter.class);
    private final FilterChain chain = mock(FilterChain.class);
    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(tokens, users, new JwtUtil(tokens), listenerGate, errors, access);
    private final UUID applicationId = UUID.randomUUID();
    private final User user = User.builder().id(UUID.randomUUID()).username("applicant").emailVerified(true)
            .status(UserStatus.PENDING_VENUE_REQUEST).roles(Set.of()).permissions(Set.of()).build();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @BeforeEach @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    private MockHttpServletRequest request(String path) {
        var request = new MockHttpServletRequest("GET", path);
        request.addHeader("Authorization", "Bearer " + tokens.generateVenueApplicationToken(new UserDetailsImpl(user), applicationId));
        return request;
    }

    @Test void pendingAndApprovedScopedTokensHaveZeroAuthoritiesAndOnlyOwnApplicationPrincipal() throws Exception {
        when(users.loadUserById(user.getId())).thenReturn(new UserDetailsImpl(user));
        when(access.isAccessible(user, applicationId)).thenReturn(true);
        for (UserStatus status : new UserStatus[]{UserStatus.PENDING_VENUE_REQUEST, UserStatus.ACTIVE}) {
            user.setStatus(status);
            if (status == UserStatus.ACTIVE) user.setRoles(Set.of(Role.builder().name("ROLE_VENUE").build()));
            var request = request(VenueApplicationRequestPolicy.BASE + applicationId);
            filter.doFilterInternal(request, response, chain);
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            assertThat(authentication.getPrincipal()).isInstanceOf(VenueApplicationPrincipal.class);
            assertThat(((VenueApplicationPrincipal) authentication.getPrincipal()).getApplicationId()).isEqualTo(applicationId);
            assertThat(authentication.getAuthorities()).isEmpty();
            verify(chain).doFilter(request, response);
        }
        verifyNoInteractions(errors, listenerGate);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/user/notifications", "/api/v1/user/dm", "/api/v1/user/venue-application",
            "/api/v1/auth/login", "/api/v1/auth/complete-google-profile", "/api/v1/cities", "/ws", "/api/v1/venue-application-session/applications/00000000-0000-4000-8000-000000000001"})
    void scopedBearerStopsBeforeAnyGenericOrOtherApplicationEndpoint(String path) throws Exception {
        var request = request(path);
        filter.doFilterInternal(request, response, chain);
        verify(errors).write(request, response, ErrorType.FORBIDDEN_ACCESS);
        verifyNoInteractions(chain, users, access);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test void revokedDeletedWrongOwnerOrSupersededSourceCannotAuthenticate() throws Exception {
        when(users.loadUserById(user.getId())).thenReturn(new UserDetailsImpl(user));
        when(access.isAccessible(user, applicationId)).thenReturn(false);
        var request = request(VenueApplicationRequestPolicy.BASE + applicationId);
        filter.doFilterInternal(request, response, chain);
        verify(errors).write(request, response, ErrorType.UNAUTHORIZED);
        verifyNoInteractions(chain);
    }


    @Test void approvedScopeCannotBypassPublicAuthOrProfileCompletionWithPost() throws Exception {
        user.setStatus(UserStatus.ACTIVE);
        user.setRoles(Set.of(Role.builder().name("ROLE_VENUE").build()));
        for (String path : new String[]{"/api/v1/auth/login", "/api/v1/auth/complete-google-profile", "/api/v1/user/notifications"}) {
            var request = request(path);
            request.setMethod("POST");
            filter.doFilterInternal(request, response, chain);
            verify(errors).write(request, response, ErrorType.FORBIDDEN_ACCESS);
        }
        verifyNoInteractions(chain, users, access);
    }

    @Test void unavailableSourceDoesNotMasqueradeAsAuthenticationFailure() {
        when(users.loadUserById(user.getId())).thenReturn(new UserDetailsImpl(user));
        when(access.isAccessible(user, applicationId)).thenThrow(new DataAccessResourceFailureException("unavailable"));
        assertThatThrownBy(() -> filter.doFilterInternal(request(VenueApplicationRequestPolicy.BASE + applicationId), response, chain))
                .isInstanceOf(DataAccessResourceFailureException.class);
        verifyNoInteractions(errors, chain);
    }

    @Test void downstreamArgumentFailureIsNotCaughtAsMalformedToken() throws Exception {
        when(users.loadUserById(user.getId())).thenReturn(new UserDetailsImpl(user));
        when(access.isAccessible(user, applicationId)).thenReturn(true);
        var request = request(VenueApplicationRequestPolicy.BASE + applicationId);
        doThrow(new IllegalArgumentException("endpoint failure")).when(chain).doFilter(request, response);
        assertThatThrownBy(() -> filter.doFilterInternal(request, response, chain)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(errors);
    }
}
