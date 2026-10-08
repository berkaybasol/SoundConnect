package com.berkayb.soundconnect.modules.feed.venue.api;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.feed.musician.abuse.MusicianFeedRateLimitGuard;
import com.berkayb.soundconnect.modules.feed.musician.api.*;
import com.berkayb.soundconnect.modules.feed.musician.core.MusicianFeedService;
import com.berkayb.soundconnect.modules.feed.musician.delivery.MusicianFeedTelemetryService;
import com.berkayb.soundconnect.modules.feed.musician.feedback.MusicianFeedFeedbackService;
import com.berkayb.soundconnect.modules.feed.musician.feedback.MusicianFeedMutedAuthorsService;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.RateLimitedException;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = VenueFeedController.class, excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = com.berkayb.soundconnect.auth.security.JwtAuthenticationFilter.class))
@AutoConfigureMockMvc(addFilters = false)
@Import(VenueFeedControllerTest.MethodSecurity.class)
class VenueFeedControllerTest {
    private static final UUID TARGET = UUID.fromString("38cbba49-8fd4-4219-8f21-a6a40acfd967");
    private static final String ITEM = "TRACK:" + TARGET;
    private static final UUID EVENT = UUID.fromString("48cbba49-8fd4-4219-8f21-a6a40acfd967");
    private static final MusicianFeedFeedbackRequest FEEDBACK = new MusicianFeedFeedbackRequest(
            MusicianFeedFeedbackAction.HIDE, null, "signed.delivery");
    private static final MusicianFeedTelemetryRequest TELEMETRY = new MusicianFeedTelemetryRequest(
            EVENT, "signed.delivery", MusicianFeedTelemetryEventType.IMPRESSION, null);

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurity { }

    @Autowired MockMvc mvc;
    @MockitoBean MusicianFeedService feed;
    @MockitoBean MusicianFeedFeedbackService feedback;
    @MockitoBean MusicianFeedMutedAuthorsService mutedAuthors;
    @MockitoBean MusicianFeedTelemetryService telemetry;
    @MockitoBean MusicianFeedRateLimitGuard rateLimitGuard;

    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @Test
    void venuePageUsesSharedContractAndAudienceSpecificServiceAfterContinuationBudget() throws Exception {
        UUID viewer = authenticate("ROLE_VENUE");
        UUID session = UUID.randomUUID();
        when(feed.getForVenue(viewer, 20, "signed.cursor", List.of("TRACK", "COLLAB")))
                .thenReturn(new MusicianFeedPageResponse(1, "venue-v1.0.0", session,
                        Instant.parse("2026-09-14T12:00:00Z"), List.of(), "next.cursor", true));
        mvc.perform(get("/api/v1/feed/venue").param("limit", "20").param("cursor", "signed.cursor")
                        .param("supportedItemTypes", "TRACK", "COLLAB"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("Cache-Control", containsString("private")))
                .andExpect(jsonPath("$.data.schemaVersion").value(1))
                .andExpect(jsonPath("$.data.feedSessionId").value(session.toString()))
                .andExpect(jsonPath("$.data.nextCursor").value("next.cursor"))
                .andExpect(jsonPath("$.data.hasMore").value(true));
        var order = inOrder(rateLimitGuard, feed);
        order.verify(rateLimitGuard).checkPage(viewer, true);
        order.verify(feed).getForVenue(viewer, 20, "signed.cursor", List.of("TRACK", "COLLAB"));
        verifyNoMoreInteractions(feed);
    }

    @Test
    void pageRequiresRendererCapabilitiesAndPropagatesInvalidAudienceCursorWithoutData() throws Exception {
        UUID viewer = authenticate("ROLE_VENUE");
        mvc.perform(get("/api/v1/feed/venue")).andExpect(status().isBadRequest());
        verifyNoInteractions(feed, rateLimitGuard);
        when(feed.getForVenue(eq(viewer), isNull(), eq("musician.cursor"), eq(List.of("TRACK"))))
                .thenThrow(new SoundConnectException(ErrorType.BAD_REQUEST));
        mvc.perform(get("/api/v1/feed/venue").param("cursor", "musician.cursor").param("supportedItemTypes", "TRACK"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void mutedListUsesVenueAuthorizationAndPrivateEnvelope() throws Exception {
        UUID viewer = authenticate("ROLE_VENUE");
        when(mutedAuthors.getForVenue(viewer, 30, "muted.cursor"))
                .thenReturn(new MusicianFeedMutedAuthorsResponse(List.of(), null, false));
        mvc.perform(get("/api/v1/feed/venue/muted-authors").param("limit", "30").param("cursor", "muted.cursor"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(header().string("Cache-Control", containsString("private")));
        verify(mutedAuthors).getForVenue(viewer, 30, "muted.cursor");
        verifyNoMoreInteractions(mutedAuthors);
    }

    @ParameterizedTest
    @EnumSource(value = Route.class, names = {"FEEDBACK", "MUTE", "UNMUTE", "TELEMETRY"})
    void writesUseTheSameAccountBudgetAndVenueGuardedBusinessPath(Route route) throws Exception {
        UUID viewer = authenticate("ROLE_VENUE");
        mvc.perform(request(route)).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        var order = inOrder(rateLimitGuard, feedback, telemetry);
        if (route == Route.TELEMETRY) order.verify(rateLimitGuard).checkTelemetry(viewer);
        else order.verify(rateLimitGuard).checkFeedback(viewer);
        switch (route) {
            case FEEDBACK -> order.verify(feedback).recordItemForVenue(viewer, ITEM, FEEDBACK);
            case MUTE -> order.verify(feedback).muteForVenue(viewer, "MUSICIAN", TARGET);
            case UNMUTE -> order.verify(feedback).unmuteForVenue(viewer, "MUSICIAN", TARGET);
            case TELEMETRY -> order.verify(telemetry).recordForVenue(viewer, TELEMETRY);
            default -> throw new AssertionError(route);
        }
        verifyNoMoreInteractions(rateLimitGuard, feedback, telemetry);
    }

    @ParameterizedTest
    @EnumSource(value = Route.class, names = {"PAGE", "FEEDBACK", "MUTE", "UNMUTE", "TELEMETRY"})
    void rateLimitRejectionPreventsAnyReadOrWriteAndKeepsRetryAdvice(Route route) throws Exception {
        UUID viewer = authenticate("ROLE_VENUE");
        var failure = new RateLimitedException(ErrorType.MUSICIAN_FEED_RATE_LIMITED, 4L);
        switch (route) {
            case PAGE -> doThrow(failure).when(rateLimitGuard).checkPage(viewer, false);
            case TELEMETRY -> doThrow(failure).when(rateLimitGuard).checkTelemetry(viewer);
            default -> doThrow(failure).when(rateLimitGuard).checkFeedback(viewer);
        }
        mvc.perform(request(route)).andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "4"));
        verifyNoInteractions(feed, feedback, mutedAuthors, telemetry);
    }

    @ParameterizedTest
    @EnumSource(Route.class)
    void everyVenueRouteRejectsMusiciansOtherRolesAndAnonymousBeforeServices(Route route) throws Exception {
        for (String role : List.of("ROLE_MUSICIAN", "ROLE_LISTENER", "ROLE_STUDIO", "ROLE_ADMIN")) {
            authenticate(role);
            mvc.perform(request(route)).andExpect(status().isForbidden());
        }
        SecurityContextHolder.clearContext();
        mvc.perform(request(route)).andExpect(status().isUnauthorized());
        verifyNoInteractions(rateLimitGuard, feed, feedback, mutedAuthors, telemetry);
    }

    private MockHttpServletRequestBuilder request(Route route) {
        return switch (route) {
            case PAGE -> get("/api/v1/feed/venue").param("supportedItemTypes", "TRACK");
            case MUTED -> get("/api/v1/feed/venue/muted-authors");
            case FEEDBACK -> post("/api/v1/feed/venue/items/{id}/feedback", ITEM).contentType("application/json")
                    .content("{\"action\":\"HIDE\",\"impressionToken\":\"signed.delivery\"}");
            case MUTE -> put("/api/v1/feed/venue/authors/MUSICIAN/{id}/mute", TARGET);
            case UNMUTE -> delete("/api/v1/feed/venue/authors/MUSICIAN/{id}/mute", TARGET);
            case TELEMETRY -> post("/api/v1/feed/venue/events").contentType("application/json")
                    .content("{\"clientEventId\":\"" + EVENT + "\",\"eventType\":\"IMPRESSION\",\"impressionToken\":\"signed.delivery\"}");
        };
    }

    private UUID authenticate(String role) {
        UUID viewer = UUID.randomUUID();
        var user = User.builder().id(viewer).username("venue_feed").password("unused")
                .email("venue-feed@example.test").emailVerified(true).status(UserStatus.ACTIVE)
                .roles(Set.of(Role.builder().name(role).build())).build();
        var principal = UserDetailsImpl.fromUser(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        return viewer;
    }

    enum Route { PAGE, MUTED, FEEDBACK, MUTE, UNMUTE, TELEMETRY }
}
