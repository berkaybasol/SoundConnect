package com.berkayb.soundconnect.modules.feed.musician.feedback;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.feed.musician.abuse.MusicianFeedRateLimitGuard;
import com.berkayb.soundconnect.modules.feed.musician.api.*;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = MusicianFeedFeedbackController.class, excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE, classes = com.berkayb.soundconnect.auth.security.JwtAuthenticationFilter.class))
@AutoConfigureMockMvc(addFilters = false)
@Import(MusicianFeedMutedAuthorsControllerTest.MethodSecurity.class)
class MusicianFeedMutedAuthorsControllerTest {
    @TestConfiguration @EnableMethodSecurity static class MethodSecurity { }
    @Autowired MockMvc mvc;
    @MockitoBean MusicianFeedFeedbackService feedback;
    @MockitoBean MusicianFeedMutedAuthorsService mutedAuthors;
    @MockitoBean MusicianFeedRateLimitGuard rateLimitGuard;

    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @Test
    void servesPrivateViewerScopedListWithMaskedUnavailableRows() throws Exception {
        UUID viewer = authenticate("ROLE_MUSICIAN");
        UUID profile = UUID.randomUUID();
        when(mutedAuthors.get(viewer, 30, "opaque.cursor")).thenReturn(new MusicianFeedMutedAuthorsResponse(
                List.of(new MusicianFeedMutedAuthorResponse("LISTENER", profile,
                        "must not escape", "https://private.test/avatar", false, Instant.parse("2026-09-13T12:00:00Z"))), null, false));
        mvc.perform(get("/api/v1/feed/musician/muted-authors").param("limit", "30").param("cursor", "opaque.cursor"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("Cache-Control", containsString("private")))
                .andExpect(jsonPath("$.data.items[0].profileType").value("LISTENER"))
                .andExpect(jsonPath("$.data.items[0].profileId").value(profile.toString()))
                .andExpect(jsonPath("$.data.items[0].displayName").value(nullValue()))
                .andExpect(jsonPath("$.data.items[0].avatarUrl").value(nullValue()))
                .andExpect(jsonPath("$.data.items[0].available").value(false))
                .andExpect(jsonPath("$.data.items[0].mutedAt").value("2026-09-13T12:00:00Z"))
                .andExpect(jsonPath("$.data.nextCursor").value(nullValue()))
                .andExpect(jsonPath("$.data.hasMore").value(false));
        verify(mutedAuthors).get(viewer, 30, "opaque.cursor");
        verifyNoInteractions(rateLimitGuard, feedback);
    }

    @Test
    void rejectsNonMusiciansAndAnonymousRequestsBeforeReadingPreferences() throws Exception {
        authenticate("ROLE_LISTENER");
        mvc.perform(get("/api/v1/feed/musician/muted-authors")).andExpect(status().isForbidden());
        SecurityContextHolder.clearContext();
        mvc.perform(get("/api/v1/feed/musician/muted-authors")).andExpect(status().isUnauthorized());
        verifyNoInteractions(mutedAuthors, feedback, rateLimitGuard);
    }

    @Test
    void existingUnmuteRouteStillDelegatesTheAuthenticatedViewerAndProfileIdentity() throws Exception {
        UUID viewer = authenticate("ROLE_MUSICIAN");
        UUID profile = UUID.randomUUID();
        mvc.perform(delete("/api/v1/feed/musician/authors/LISTENER/{id}/mute", profile))
                .andExpect(status().isOk());
        verify(feedback).unmute(viewer, "LISTENER", profile);
        verify(rateLimitGuard).checkFeedback(viewer);
        verifyNoInteractions(mutedAuthors);
    }

    private UUID authenticate(String role) {
        UUID id = UUID.randomUUID();
        User user = User.builder().id(id).username("mute_owner").password("unused")
                .email("mute-owner@example.test").emailVerified(true).status(UserStatus.ACTIVE)
                .roles(Set.of(Role.builder().name(role).build())).build();
        UserDetailsImpl principal = UserDetailsImpl.fromUser(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        return id;
    }
}
