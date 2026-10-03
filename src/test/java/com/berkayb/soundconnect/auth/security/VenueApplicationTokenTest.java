package com.berkayb.soundconnect.auth.security;

import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import com.berkayb.soundconnect.auth.service.CustomUserDetailsService;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.support.ListenerProfileChoiceStatusReader;
import com.berkayb.soundconnect.shared.realtime.WebSocketSecurityInterceptor;
import com.berkayb.soundconnect.shared.realtime.WebSocketSessionRegistry;
import com.berkayb.soundconnect.shared.realtime.WebSocketSubscriptionAuthorizer;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.BadCredentialsException;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class VenueApplicationTokenTest {
    static final String SECRET = "0123456789abcdef0123456789abcdef";

    static JwtTokenProvider provider() {
        var provider = new JwtTokenProvider();
        ReflectionTestUtils.setField(provider, "jwtSecret", SECRET);
        ReflectionTestUtils.setField(provider, "jwtExpiration", 60_000L);
        ReflectionTestUtils.setField(provider, "jwtIssuer", "soundconnect-test");
        return provider;
    }

    @Test void scopedCredentialRemainsSeparateFromOrdinaryBearerAfterApproval() {
        var provider = provider();
        var user = User.builder().id(UUID.randomUUID()).status(UserStatus.PENDING_VENUE_REQUEST)
                .roles(Set.of()).permissions(Set.of()).build();
        var applicationId = UUID.randomUUID();
        var token = provider.generateVenueApplicationToken(new UserDetailsImpl(user), applicationId);
        assertThat(provider.validateToken(token)).isFalse();
        assertThat(provider.getVenueApplicationClaims(token)).isEqualTo(new JwtTokenProvider.VenueApplicationClaims(user.getId(), applicationId));
        user.setStatus(UserStatus.ACTIVE);
        assertThat(provider.validateToken(token)).isFalse();
        var ordinary = provider.generateToken(new UserDetailsImpl(user));
        assertThat(provider.validateToken(ordinary)).isTrue();
        assertThat(provider.getVenueApplicationClaims(ordinary)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNKNOWN", "", "VENUE_APPLICATION "})
    void unknownScopesAreNeverOrdinaryOrApplicationCredentials(String scope) {
        var token = Jwts.builder().setSubject(UUID.randomUUID().toString()).claim("scope", scope)
                .claim("applicationId", UUID.randomUUID().toString()).setIssuer("soundconnect-test")
                .setExpiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)), SignatureAlgorithm.HS256).compact();
        assertThat(provider().validateToken(token)).isFalse();
        assertThatThrownBy(() -> provider().getVenueApplicationClaims(token)).isInstanceOf(RuntimeException.class);
    }

    @Test void missingExpiryAndRoleBearingScopedClaimsFailClosed() {
        for (boolean includeRoles : new boolean[]{false, true}) {
            var builder = Jwts.builder().setSubject(UUID.randomUUID().toString()).claim("scope", "VENUE_APPLICATION")
                    .claim("applicationId", UUID.randomUUID().toString()).setIssuer("soundconnect-test");
            if (includeRoles) builder.claim("roles", Set.of("ROLE_VENUE")).setExpiration(new Date(System.currentTimeMillis() + 60_000));
            String token = builder.signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)), SignatureAlgorithm.HS256).compact();
            assertThat(provider().validateToken(token)).isFalse();
            assertThatThrownBy(() -> provider().getVenueApplicationClaims(token)).isInstanceOf(RuntimeException.class);
        }
    }

    @Test void signedApplicationCredentialCannotOpenWebSocketEvenAfterApproval() {
        var provider = provider();
        var user = User.builder().id(UUID.randomUUID()).status(UserStatus.PENDING_VENUE_REQUEST).build();
        var token = provider.generateVenueApplicationToken(new UserDetailsImpl(user), UUID.randomUUID());
        user.setStatus(UserStatus.ACTIVE);
        var users = mock(CustomUserDetailsService.class);
        var registry = new WebSocketSessionRegistry();
        var interceptor = new WebSocketSecurityInterceptor(provider, users, mock(WebSocketSubscriptionAuthorizer.class),
                registry, mock(ListenerProfileChoiceStatusReader.class));
        var headers = StompHeaderAccessor.create(StompCommand.CONNECT);
        headers.setNativeHeader("Authorization", "Bearer " + token);
        headers.setSessionId("restricted-application");
        headers.setLeaveMutable(true);
        var message = MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
        assertThatThrownBy(() -> interceptor.preSend(message, null)).isInstanceOf(BadCredentialsException.class);
        assertThat(registry.find("restricted-application")).isEmpty();
        verifyNoInteractions(users);
    }
}
