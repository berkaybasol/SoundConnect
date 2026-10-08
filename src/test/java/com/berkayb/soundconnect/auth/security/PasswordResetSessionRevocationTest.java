package com.berkayb.soundconnect.auth.security;

import com.berkayb.soundconnect.auth.service.CustomUserDetailsService;
import com.berkayb.soundconnect.modules.application.venueapplication.service.VenueApplicationSessionAccess;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.shared.security.SecurityErrorResponseWriter;
import com.berkayb.soundconnect.shared.util.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PasswordResetSessionRevocationTest {
    static final String SECRET = "session-regression-synthetic-secret-at-least-32-bytes";
    static final String ISSUER = "session-regression";
    final User user = User.builder().id(UUID.randomUUID()).username("session-fixture")
            .email("session@example.invalid").password("old-hash")
            .status(UserStatus.ACTIVE).emailVerified(true)
            .roles(Set.of(Role.builder().name("ROLE_MUSICIAN").build())).build();
    final JwtTokenProvider tokens = provider();

    @AfterEach void clearSecurityContext() { SecurityContextHolder.clearContext(); }

    @Test void previouslyIssuedTokenCannotAuthenticateAfterCredentialRevocation() throws Exception {
        String oldToken = tokens.generateToken(new UserDetailsImpl(user));
        user.setPassword("new-hash");
        user.setSessionVersion(1L);
        assertThat(authenticate(oldToken, "/api/v1/user/me").getStatus()).isEqualTo(401);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test void allDevicesAndRepeatedOldTokenUseAreRejectedWhileNewLoginTokenWorks() throws Exception {
        String firstDevice = tokens.generateToken(new UserDetailsImpl(user));
        String secondDevice = signed(null);
        user.setSessionVersion(1L);
        for (String token : new String[]{firstDevice, secondDevice, firstDevice, secondDevice}) {
            assertThat(authenticate(token, "/api/v1/user/me").getStatus()).isEqualTo(401);
        }
        assertThat(authenticate(tokens.generateToken(new UserDetailsImpl(user)), "/api/v1/user/me")
                .getStatus()).isEqualTo(200);
    }

    @Test void legacyTokenWorksAtZeroAndIsRejectedAfterFirstResetEvenOnPublicRead() throws Exception {
        String legacy = signed(null);
        assertThat(tokens.getSessionVersionFromToken(legacy)).isZero();
        assertThat(authenticate(legacy, "/api/v1/public/unrelated").getStatus()).isEqualTo(200);
        user.setSessionVersion(1L);
        var response = authenticate(legacy, "/api/v1/public/unrelated");
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getContentAsString()).doesNotContain(legacy, user.getEmail(), user.getPassword());
    }

    @Test void pendingApplicationCredentialAlsoCarriesAndEnforcesRevision() throws Exception {
        UUID application = UUID.randomUUID();
        String token = tokens.generateVenueApplicationToken(new UserDetailsImpl(user), application);
        assertThat(tokens.getSessionVersionFromToken(token)).isZero();
        assertThat(authenticate(token, VenueApplicationRequestPolicy.BASE + application).getStatus()).isEqualTo(200);
        user.setSessionVersion(1L);
        assertThat(authenticate(token, VenueApplicationRequestPolicy.BASE + application).getStatus()).isEqualTo(401);
        String current = tokens.generateVenueApplicationToken(new UserDetailsImpl(user), application);
        assertThat(tokens.getSessionVersionFromToken(current)).isEqualTo(1L);
        assertThat(authenticate(current, VenueApplicationRequestPolicy.BASE + application).getStatus()).isEqualTo(200);
    }

    @ParameterizedTest @ValueSource(strings = {"-1", "0", "1", "1.0", "9223372036854775808"})
    void textualRevisionsCannotBeCoercedToNumbers(String malformed) {
        assertThatThrownBy(() -> tokens.getSessionVersionFromToken(signed(malformed)))
                .isInstanceOf(MalformedJwtException.class);
    }

    @Test void fractionalAndNegativeRevisionsFailClosed() {
        for (Object malformed : new Object[]{-1, -1L, 0.5, true}) {
            assertThatThrownBy(() -> tokens.getSessionVersionFromToken(signed(malformed)))
                    .isInstanceOf(MalformedJwtException.class);
        }
    }

    private MockHttpServletResponse authenticate(String token, String path) throws Exception {
        SecurityContextHolder.clearContext();
        CustomUserDetailsService users = mock(CustomUserDetailsService.class);
        when(users.loadUserById(user.getId())).thenReturn(new UserDetailsImpl(user));
        var applications = mock(VenueApplicationSessionAccess.class);
        when(applications.isAccessible(eq(user), any())).thenReturn(true);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(tokens, users, new JwtUtil(tokens),
                mock(ListenerProfileChoiceGate.class),
                new SecurityErrorResponseWriter(new ObjectMapper().findAndRegisterModules()), applications);
        AtomicBoolean reached = new AtomicBoolean();
        filter.doFilter(request, response, (req, res) -> {
            reached.set(true);
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        });
        assertThat(reached.get()).isEqualTo(response.getStatus() == 200);
        return response;
    }

    private String signed(Object revision) {
        var builder = Jwts.builder().setSubject(user.getId().toString()).setIssuer(ISSUER)
                .setIssuedAt(new Date()).setExpiration(new Date(System.currentTimeMillis() + 600000));
        if (revision != null) builder.claim("sessionVersion", revision);
        return builder.signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)), SignatureAlgorithm.HS256).compact();
    }

    static JwtTokenProvider provider() {
        JwtTokenProvider provider = new JwtTokenProvider();
        ReflectionTestUtils.setField(provider, "jwtSecret", SECRET);
        ReflectionTestUtils.setField(provider, "jwtIssuer", ISSUER);
        ReflectionTestUtils.setField(provider, "jwtExpiration", 600000L);
        return provider;
    }
}
