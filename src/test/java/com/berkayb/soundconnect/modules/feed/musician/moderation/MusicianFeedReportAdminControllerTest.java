package com.berkayb.soundconnect.modules.feed.musician.moderation;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.role.entity.Permission;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.fasterxml.jackson.databind.ObjectMapper;
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

@WebMvcTest(controllers = MusicianFeedReportAdminController.class,
        properties = "app.feed.musician.enabled=false", excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE, classes = com.berkayb.soundconnect.auth.security.JwtAuthenticationFilter.class))
@AutoConfigureMockMvc(addFilters = false)
@Import(MusicianFeedReportAdminControllerTest.MethodSecurity.class)
class MusicianFeedReportAdminControllerTest {
    private static final UUID REPORT = UUID.fromString("a9a8dcf7-e9e4-45f0-858f-9b53b5ca1bf8");
    private static final UUID REQUEST = UUID.fromString("2c41968b-9d56-40d6-8f2a-b6dbb797a3d2");
    private static final String BASE = "/api/v1/admin/musician-feed/reports";
    @TestConfiguration @EnableMethodSecurity static class MethodSecurity { }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean MusicianFeedReportModerationService service;

    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @ParameterizedTest
    @EnumSource(Route.class)
    void roleOnlyAndAnonymousRequestsCannotReadEvidenceOrMutate(Route route) throws Exception {
        authenticate("ROLE_ADMIN", false);
        mvc.perform(request(route)).andExpect(status().isForbidden());
        authenticate("ROLE_MUSICIAN", false);
        mvc.perform(request(route)).andExpect(status().isForbidden());
        SecurityContextHolder.clearContext();
        mvc.perform(request(route)).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @EnumSource(Route.class)
    void explicitPermissionServesPrivateResponsesEvenWhenFeedServingIsDisabled(Route route) throws Exception {
        UUID actor = authenticate("ROLE_ADMIN", true);
        var summary = new MusicianFeedReportSummary(REPORT, 0, MusicianFeedReportStatus.NEW,
                "TRACK:sample", "TRACK", "MEDIA", UUID.randomUUID(), null,
                Instant.parse("2026-09-13T12:00:00Z"), "Örnek rapor", null);
        var detail = new MusicianFeedReportDetail(summary, UUID.randomUUID(), mapper.readTree("{\"payload\":{\"omitted\":true}}"),
                null, List.of(MusicianFeedReportDecision.START_REVIEW, MusicianFeedReportDecision.DISMISS), false, List.of());
        switch (route) {
            case LIST -> when(service.list(actor, null, null, null, null))
                    .thenReturn(new MusicianFeedReportPage(List.of(summary), null, false));
            case DETAIL -> when(service.detail(actor, REPORT)).thenReturn(detail);
            case REVIEW -> when(service.review(actor, REPORT, validCommand())).thenReturn(detail);
        }
        mvc.perform(request(route))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("Cache-Control", containsString("private")))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isMap());
        switch (route) {
            case LIST -> verify(service).list(actor, null, null, null, null);
            case DETAIL -> verify(service).detail(actor, REPORT);
            case REVIEW -> verify(service).review(actor, REPORT, validCommand());
        }
        verifyNoMoreInteractions(service);
    }

    @Test
    void malformedRouteFilterAndReviewBodyNeverReachService() throws Exception {
        authenticate("ROLE_ADMIN", true);
        mvc.perform(get(BASE).param("status", "UNKNOWN")).andExpect(status().isBadRequest());
        mvc.perform(get(BASE).param("itemType", "UNKNOWN")).andExpect(status().isBadRequest());
        mvc.perform(get(BASE + "/not-a-uuid")).andExpect(status().isBadRequest());
        for (String body : List.of("{}", """
                {"clientRequestId":"%s","expectedVersion":-1,"decision":"DISMISS","resolutionNote":"Valid note"}
                """.formatted(REQUEST), """
                {"clientRequestId":"%s","expectedVersion":0,"decision":"DISMISS","resolutionNote":"tiny"}
                """.formatted(REQUEST), """
                {"clientRequestId":"%s","expectedVersion":0,"decision":"INVALID","resolutionNote":"Valid note"}
                """.formatted(REQUEST))) {
            mvc.perform(post(BASE + "/{id}/review", REPORT).contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }

    @Test
    void missingReportAndConflictingDecisionUseDedicatedStableErrors() throws Exception {
        UUID actor = authenticate("ROLE_ADMIN", true);
        when(service.detail(actor, REPORT)).thenThrow(new SoundConnectException(ErrorType.MUSICIAN_FEED_REPORT_NOT_FOUND));
        when(service.review(actor, REPORT, validCommand())).thenThrow(new SoundConnectException(ErrorType.MUSICIAN_FEED_REPORT_CONFLICT));
        mvc.perform(request(Route.DETAIL)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(1321));
        mvc.perform(request(Route.REVIEW)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(1322));
    }

    private MockHttpServletRequestBuilder request(Route route) throws Exception {
        return switch (route) {
            case LIST -> get(BASE);
            case DETAIL -> get(BASE + "/{id}", REPORT);
            case REVIEW -> post(BASE + "/{id}/review", REPORT).contentType("application/json")
                    .content(mapper.writeValueAsBytes(validCommand()));
        };
    }
    private MusicianFeedReportReviewRequest validCommand() {
        return new MusicianFeedReportReviewRequest(REQUEST, 0L, MusicianFeedReportDecision.DISMISS, "İhlal tespit edilmedi.");
    }
    private UUID authenticate(String roleName, boolean permitted) {
        UUID id = UUID.randomUUID();
        var permissions = permitted ? Set.of(Permission.builder().name("MANAGE_MUSICIAN_FEED_REPORTS").build())
                : Set.<Permission>of();
        User user = User.builder().id(id).username("moderator").password("unused")
                .email("moderator@example.test").emailVerified(true).status(UserStatus.ACTIVE)
                .roles(Set.of(Role.builder().name(roleName).permissions(permissions).build())).build();
        var principal = UserDetailsImpl.fromUser(user);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
        return id;
    }
    private enum Route { LIST, DETAIL, REVIEW }
}
