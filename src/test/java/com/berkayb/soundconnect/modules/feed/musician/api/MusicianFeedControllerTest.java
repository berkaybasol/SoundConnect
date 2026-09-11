package com.berkayb.soundconnect.modules.feed.musician.api;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.feed.musician.abuse.MusicianFeedRateLimitGuard;
import com.berkayb.soundconnect.modules.feed.musician.core.MusicianFeedService;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
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
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.RateLimitedException;
import com.berkayb.soundconnect.shared.exception.ServiceUnavailableRetryException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.*;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = MusicianFeedController.class, excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = com.berkayb.soundconnect.auth.security.JwtAuthenticationFilter.class))
@AutoConfigureMockMvc(addFilters = false)
@Import(MusicianFeedControllerTest.MethodSecurity.class)
class MusicianFeedControllerTest {
    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurity { }

    @Autowired MockMvc mvc;
    @MockitoBean MusicianFeedService service;
    @MockitoBean MusicianFeedRateLimitGuard rateLimitGuard;

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void musicianGetsPrivateVersionedEnvelopeAndExactClientCapabilitiesReachService() throws Exception {
        UUID userId = authenticate("ROLE_MUSICIAN");
        UUID sessionId = UUID.randomUUID();
        UUID trackId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        var item = new MusicianFeedItemResponse("TRACK:" + trackId, MusicianFeedItemType.TRACK, 1,
                Instant.parse("2026-09-11T12:00:00Z"),
                new MusicianFeedItemResponse.Reason(MusicianFeedReasonCode.FOLLOWING_PUBLICATION,
                        List.of(), 0), null, new MusicianFeedItemResponse.Target("MEDIA", mediaId),
                null, null, List.of(MusicianFeedFeedbackAction.HIDE),
                new MusicianFeedPayloads.Track(trackId, mediaId, "Demo", "https://media.test/demo.mp3",
                        180, 120));
        when(service.get(userId, 20, null, List.of("TRACK", "COLLAB")))
                .thenReturn(new MusicianFeedPageResponse(1, "musician-v1.0.0", sessionId,
                        Instant.parse("2026-09-11T12:01:00Z"), List.of(item), "opaque.next", true));

        mvc.perform(get("/api/v1/feed/musician")
                        .param("limit", "20")
                        .param("supportedItemTypes", "TRACK", "COLLAB"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("Cache-Control", containsString("private")))
                .andExpect(jsonPath("$.data.schemaVersion").value(1))
                .andExpect(jsonPath("$.data.algorithmVersion").value("musician-v1.0.0"))
                .andExpect(jsonPath("$.data.feedSessionId").value(sessionId.toString()))
                .andExpect(jsonPath("$.data.items[0].id").value("TRACK:" + trackId))
                .andExpect(jsonPath("$.data.items[0].type").value("TRACK"))
                .andExpect(jsonPath("$.data.items[0].target.type").value("MEDIA"))
                .andExpect(jsonPath("$.data.items[0].payload.trackId").value(trackId.toString()))
                .andExpect(jsonPath("$.data.nextCursor").value("opaque.next"))
                .andExpect(jsonPath("$.data.hasMore").value(true));
        InOrder boundaryOrder = inOrder(rateLimitGuard, service);
        boundaryOrder.verify(rateLimitGuard).checkPage(userId, false);
        boundaryOrder.verify(service).get(userId, 20, null, List.of("TRACK", "COLLAB"));
    }

    @Test
    void rendererAdvertisementIsRequiredAtTheHttpBoundary() throws Exception {
        authenticate("ROLE_MUSICIAN");

        mvc.perform(get("/api/v1/feed/musician"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void completionPayloadUsesTheLockedScalarContractWithoutPreferenceInternals() throws Exception {
        UUID userId = authenticate("ROLE_MUSICIAN");
        UUID profileId = UUID.randomUUID();
        var task = new MusicianFeedPayloads.CompletionTask("PORTFOLIO", "Portföyünü oluştur",
                "Herkese açık bir parça veya profil medyası ekle.", "Portföye git",
                "/profile/musician/portfolio", 4, false);
        var item = new MusicianFeedItemResponse("PROFILE_COMPLETION:" + profileId,
                MusicianFeedItemType.PROFILE_COMPLETION, 1, Instant.parse("2026-09-11T12:00:00Z"),
                new MusicianFeedItemResponse.Reason(MusicianFeedReasonCode.PROFILE_INCOMPLETE,
                        List.of(), 0), null, new MusicianFeedItemResponse.Target("PROFILE", profileId),
                null, null, List.of(MusicianFeedFeedbackAction.HIDE),
                new MusicianFeedPayloads.Completion(2, 5, List.of(task)));
        when(service.get(userId, 20, null, List.of("PROFILE_COMPLETION")))
                .thenReturn(new MusicianFeedPageResponse(1, "musician-v1.0.0", UUID.randomUUID(),
                        Instant.parse("2026-09-11T12:01:00Z"), List.of(item), null, false));

        mvc.perform(get("/api/v1/feed/musician").param("limit", "20")
                        .param("supportedItemTypes", "PROFILE_COMPLETION"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].payload.completed").value(2))
                .andExpect(jsonPath("$.data.items[0].payload.total").value(5))
                .andExpect(jsonPath("$.data.items[0].payload.tasks[0].code").value("PORTFOLIO"))
                .andExpect(jsonPath("$.data.items[0].payload.tasks[0].priority").value(4))
                .andExpect(jsonPath("$.data.items[0].payload.criteriaVersion").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].payload.overall").doesNotExist());
    }

    @Test
    void wrongProfileRoleAndAnonymousCallerCannotReachTheService() throws Exception {
        authenticate("ROLE_LISTENER");
        mvc.perform(get("/api/v1/feed/musician").param("supportedItemTypes", "TRACK"))
                .andExpect(status().isForbidden());
        SecurityContextHolder.clearContext();
        mvc.perform(get("/api/v1/feed/musician").param("supportedItemTypes", "TRACK"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(rateLimitGuard, service);
    }

    @Test
    void validCursorUsesContinuationBudgetBeforeServiceWork() throws Exception {
        UUID userId = authenticate("ROLE_MUSICIAN");
        when(service.get(userId, 20, "signed.cursor", List.of("TRACK")))
                .thenReturn(new MusicianFeedPageResponse(1, "musician-v1.0.0", UUID.randomUUID(),
                        Instant.parse("2026-09-11T12:01:00Z"), List.of(), null, false));

        mvc.perform(get("/api/v1/feed/musician")
                        .param("limit", "20")
                        .param("cursor", "signed.cursor")
                        .param("supportedItemTypes", "TRACK"))
                .andExpect(status().isOk());

        InOrder boundaryOrder = inOrder(rateLimitGuard, service);
        boundaryOrder.verify(rateLimitGuard).checkPage(userId, true);
        boundaryOrder.verify(service).get(userId, 20, "signed.cursor", List.of("TRACK"));
    }

    @Test
    void limiterRejectionReturnsRetryAfterAndNeverCallsFeedService() throws Exception {
        UUID userId = authenticate("ROLE_MUSICIAN");
        doThrow(new RateLimitedException(ErrorType.MUSICIAN_FEED_RATE_LIMITED, 7L))
                .when(rateLimitGuard).checkPage(userId, false);

        mvc.perform(get("/api/v1/feed/musician")
                        .param("supportedItemTypes", "TRACK"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "7"))
                .andExpect(jsonPath("$.code").value(1319));

        verifyNoInteractions(service);
    }

    @Test
    void unavailableLimiterFailsClosedBeforeFeedService() throws Exception {
        UUID userId = authenticate("ROLE_MUSICIAN");
        doThrow(new ServiceUnavailableRetryException(
                ErrorType.MUSICIAN_FEED_RATE_LIMIT_UNAVAILABLE, 5L))
                .when(rateLimitGuard).checkPage(userId, false);

        mvc.perform(get("/api/v1/feed/musician")
                        .param("supportedItemTypes", "TRACK"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.code").value(1320));

        verifyNoInteractions(service);
    }

    private UUID authenticate(String roleName) {
        UUID id = UUID.randomUUID();
        User user = User.builder().id(id).username("feed_owner").password("unused")
                .email("feed-owner@example.com").emailVerified(true).status(UserStatus.ACTIVE)
                .roles(Set.of(Role.builder().name(roleName).build())).build();
        UserDetailsImpl principal = UserDetailsImpl.fromUser(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        return id;
    }
}
