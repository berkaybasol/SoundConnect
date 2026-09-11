package com.berkayb.soundconnect.modules.feed.musician.delivery;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.feed.musician.abuse.MusicianFeedRateLimitGuard;
import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedTelemetryEventType;
import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedTelemetryRequest;
import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedTelemetryResponse;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.RateLimitedException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = MusicianFeedTelemetryController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = com.berkayb.soundconnect.auth.security.JwtAuthenticationFilter.class))
@AutoConfigureMockMvc(addFilters = false)
@Import(MusicianFeedTelemetryControllerTest.MethodSecurity.class)
class MusicianFeedTelemetryControllerTest {
    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurity {
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean MusicianFeedTelemetryService service;
    @MockitoBean MusicianFeedRateLimitGuard rateLimitGuard;

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void authenticatedMusicianIsGuardedBeforeTelemetryPersistence() throws Exception {
        UUID userId = authenticate("ROLE_MUSICIAN");
        MusicianFeedTelemetryRequest request = request();
        Instant recordedAt = Instant.parse("2026-09-11T13:00:00Z");
        when(service.record(userId, request)).thenReturn(new MusicianFeedTelemetryResponse(
                UUID.randomUUID(), request.clientEventId(), request.eventType(), false, recordedAt));

        mvc.perform(post("/api/v1/feed/musician/events")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.clientEventId").value(request.clientEventId().toString()))
                .andExpect(jsonPath("$.data.eventType").value("IMPRESSION"));

        InOrder boundaryOrder = inOrder(rateLimitGuard, service);
        boundaryOrder.verify(rateLimitGuard).checkTelemetry(userId);
        boundaryOrder.verify(service).record(userId, request);
    }

    @Test
    void limiterRejectsBeforeTelemetryServiceAndProvidesRetryAfter() throws Exception {
        UUID userId = authenticate("ROLE_MUSICIAN");
        MusicianFeedTelemetryRequest request = request();
        doThrow(new RateLimitedException(ErrorType.MUSICIAN_FEED_RATE_LIMITED, 3L))
                .when(rateLimitGuard).checkTelemetry(eq(userId));

        mvc.perform(post("/api/v1/feed/musician/events")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "3"))
                .andExpect(jsonPath("$.code").value(1319));

        verifyNoInteractions(service);
    }

    @Test
    void wrongRoleCannotReachGuardOrTelemetryService() throws Exception {
        authenticate("ROLE_LISTENER");

        mvc.perform(post("/api/v1/feed/musician/events")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsBytes(request())))
                .andExpect(status().isForbidden());

        verifyNoInteractions(rateLimitGuard, service);
    }

    private MusicianFeedTelemetryRequest request() {
        return new MusicianFeedTelemetryRequest(UUID.randomUUID(), "signed.impression.token",
                MusicianFeedTelemetryEventType.IMPRESSION,
                Instant.parse("2026-09-11T12:59:55Z"));
    }

    private UUID authenticate(String roleName) {
        UUID id = UUID.randomUUID();
        User user = User.builder().id(id).username("feed_telemetry_owner").password("unused")
                .email("feed-telemetry-owner@example.com").emailVerified(true).status(UserStatus.ACTIVE)
                .roles(Set.of(Role.builder().name(roleName).build())).build();
        UserDetailsImpl principal = UserDetailsImpl.fromUser(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        return id;
    }
}
