package com.berkayb.soundconnect.modules.event.plan;

import com.berkayb.soundconnect.auth.ratelimit.AuthRateLimitFilter;
import com.berkayb.soundconnect.auth.security.JwtAuthenticationFilter;
import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.shared.config.SecurityConfig;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.security.*;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;
import java.util.List;
import java.util.UUID;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({EventPlanOwnerController.class, EventPlanPerformerController.class})
@Import({SecurityConfig.class, RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class,
        SecurityErrorResponseWriter.class, EventPlanPrivateResponseFilter.class})
class EventPlanHttpContractTest {
    @Autowired MockMvc mvc;
    @MockitoBean EventPlanService service;
    @MockitoBean EventPlanRateGuard guard;
    @MockitoBean JwtAuthenticationFilter jwt;
    @MockitoBean AuthRateLimitFilter authRateLimit;
    private final UUID actor = UUID.randomUUID(), plan = UUID.randomUUID();
    private final String ownerPath = "/api/v1/venue-owner/event-plans/" + plan;
    private final String performerPath = "/api/v1/user/event-plans/" + plan;

    @BeforeEach
    void passThroughAuthenticationFilters() throws Exception {
        doAnswer(invocation -> {
            invocation.<FilterChain>getArgument(2).doFilter(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(jwt).doFilter(any(ServletRequest.class), any(ServletResponse.class), any(FilterChain.class));
        doAnswer(invocation -> {
            invocation.<FilterChain>getArgument(2).doFilter(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(authRateLimit).doFilter(any(ServletRequest.class), any(ServletResponse.class), any(FilterChain.class));
    }

    @Test
    void privateNamespacesRejectGuestsAndWrongRolesBeforeReadingPlans() throws Exception {
        for (String path : new String[]{ownerPath, performerPath}) {
            mvc.perform(get(path).servletPath(path)).andExpect(status().isUnauthorized())
                    .andExpect(header().string("Cache-Control", containsString("no-store")));
        }
        mvc.perform(get(ownerPath).servletPath(ownerPath).with(user(principal("ROLE_MUSICIAN"))))
                .andExpect(status().isForbidden());
        mvc.perform(get(performerPath).servletPath(performerPath).with(user(principal("ROLE_VENUE"))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service, guard);
    }

    @Test
    void readsUseAuthenticatedIdentityAndRemainPrivateEvenOnDomainFailure() throws Exception {
        when(service.getOwner(actor, plan)).thenThrow(new SoundConnectException(ErrorType.EVENT_NOT_FOUND));
        mvc.perform(get(ownerPath).servletPath(ownerPath).with(user(principal("ROLE_VENUE"))))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Cache-Control", containsString("no-store")));
        verify(service).getOwner(actor, plan);
        verifyNoInteractions(guard);
    }

    @Test
    void updatePreviewRejectsGuestsAndOtherRolesBeforeReadingThePrivateLedger() throws Exception {
        String path = ownerPath + "/preview";
        mvc.perform(post(path).servletPath(path).contentType(MediaType.APPLICATION_JSON).content(previewRequestJson("7")))
                .andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control", containsString("no-store")));
        mvc.perform(post(path).servletPath(path).with(user(principal("ROLE_MUSICIAN")))
                        .contentType(MediaType.APPLICATION_JSON).content(previewRequestJson("7")))
                .andExpect(status().isForbidden()).andExpect(header().string("Cache-Control", containsString("private")));
        verifyNoInteractions(service, guard);
    }

    @Test
    void updatePreviewUsesThePreviewQuotaAndReturnsPreservedDatesWithTheActualMovedDate() throws Exception {
        String path = ownerPath + "/preview";
        var request = new EventPlanUpdateRequest(7L, previewDefinition());
        when(service.previewUpdate(actor, plan, request)).thenReturn(new EventPlanPreview(
                List.of(LocalDate.of(2026,10,9)), LocalDate.of(2026,10,28), true, Instant.parse("2026-10-01T09:00:00Z"),
                List.of(new EventPlanPreservedDate(LocalDate.of(2026,10,16), LocalDate.of(2026,10,17), EventPlanPreservationStatus.OVERRIDDEN),
                        new EventPlanPreservedDate(LocalDate.of(2026,10,1), LocalDate.of(2026,10,1), EventPlanPreservationStatus.STARTED))));
        mvc.perform(post(path).servletPath(path).with(user(principal("ROLE_VENUE")))
                        .contentType(MediaType.APPLICATION_JSON).content(previewRequestJson("7")))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", containsString("private")))
                .andExpect(jsonPath("$.data.dates[0]").value("2026-10-09"))
                .andExpect(jsonPath("$.data.preservedDates[0].scheduledDate").value("2026-10-16"))
                .andExpect(jsonPath("$.data.preservedDates[0].eventDate").value("2026-10-17"))
                .andExpect(jsonPath("$.data.preservedDates[0].status").value("OVERRIDDEN"))
                .andExpect(jsonPath("$.data.preservedDates[1].status").value("STARTED"));
        var order = inOrder(guard, service);
        order.verify(guard).checkPreview(actor);
        order.verify(service).previewUpdate(actor, plan, request);
        verify(guard, never()).check(any());
    }

    @Test
    void updatePreviewRejectsAnUnparseableRevisionBeforeQuotaOrServiceAndKeepsConflictsPrivate() throws Exception {
        String path = ownerPath + "/preview";
        mvc.perform(post(path).servletPath(path).with(user(principal("ROLE_VENUE")))
                        .contentType(MediaType.APPLICATION_JSON).content(previewRequestJson("\"7\"")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service, guard);
        when(service.previewUpdate(actor, plan, new EventPlanUpdateRequest(7L, previewDefinition())))
                .thenThrow(new SoundConnectException(ErrorType.MUSICIAN_CALENDAR_VERSION_CONFLICT));
        mvc.perform(post(path).servletPath(path).with(user(principal("ROLE_VENUE")))
                        .contentType(MediaType.APPLICATION_JSON).content(previewRequestJson("7")))
                .andExpect(status().isConflict()).andExpect(header().string("Cache-Control", containsString("no-store")));
    }

    @Test
    void originalCreatePreviewKeepsItsDefinitionBodyAndEmptyPreservedDates() throws Exception {
        String path = "/api/v1/venue-owner/event-plans/preview";
        when(service.preview(actor, previewDefinition())).thenReturn(new EventPlanPreview(
                List.of(LocalDate.of(2026,10,2)), LocalDate.of(2026,10,28), true, Instant.parse("2026-10-01T09:00:00Z")));
        mvc.perform(post(path).servletPath(path).with(user(principal("ROLE_VENUE")))
                        .contentType(MediaType.APPLICATION_JSON).content(previewDefinitionJson()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.dates[0]").value("2026-10-02"))
                .andExpect(jsonPath("$.data.preservedDates").isEmpty());
        verify(guard).checkPreview(actor);
        verify(service).preview(actor, previewDefinition());
        verify(service, never()).previewUpdate(any(), any(), any());
    }

    private EventPlanDefinition previewDefinition() {
        return new EventPlanDefinition(actor, LocalDate.of(2026,10,1), null, List.of(2,5), List.of(),
                new EventPlanTemplate("Concert", null, LocalTime.of(20,0), null, null, null, null, "Performer"));
    }

    private String previewDefinitionJson() {
        return "{\"venueId\":\"" + actor + "\",\"startDate\":\"2026-10-01\",\"weekdays\":[2,5],\"excludedDates\":[],"
                + "\"template\":{\"title\":\"Concert\",\"startTime\":\"20:00\",\"manualPerformerName\":\"Performer\"}}";
    }

    private String previewRequestJson(String revision) {
        return "{\"expectedVersion\":" + revision + ",\"definition\":" + previewDefinitionJson() + "}";
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"true\"", "\"false\"", "1", "0", "{}", "[]"})
    void destructiveStopRequiresAnActualJsonBoolean(String value) throws Exception {
        String path = ownerPath + "/stop";
        mvc.perform(post(path).servletPath(path).with(user(principal("ROLE_VENUE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0,\"cancelFuture\":" + value + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Cache-Control", containsString("no-store")));
        verifyNoInteractions(service, guard);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"true\"", "1", "{}"})
    void profilePublicationCannotBeEnabledByBooleanCoercion(String value) throws Exception {
        String path = performerPath + "/decision";
        mvc.perform(post(path).servletPath(path).with(user(principal("ROLE_MUSICIAN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0,\"decision\":\"ACCEPT\",\"showOnProfile\":" + value + "}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service, guard);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"7\"", "7.9", "true", "9223372036854775808"})
    void optimisticRevisionCannotBeCoercedOrTruncated(String value) throws Exception {
        String path = ownerPath + "/stop";
        mvc.perform(post(path).servletPath(path).with(user(principal("ROLE_VENUE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":" + value + ",\"cancelFuture\":false}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service, guard);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"5\"", "5.9", "true", "2147483648"})
    void weekdaysCannotSilentlyChangeThroughJsonCoercion(String value) throws Exception {
        String path = "/api/v1/venue-owner/event-plans/preview";
        mvc.perform(post(path).servletPath(path).with(user(principal("ROLE_VENUE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"weekdays\":[" + value + "]}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service, guard);
    }

    @Test
    void acceptedFalseRemainsFalseAndWritesAreRateLimitedBeforeService() throws Exception {
        String path = performerPath + "/decision";
        mvc.perform(post(path).servletPath(path).with(user(principal("ROLE_MUSICIAN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":7,\"decision\":\"ACCEPT\",\"showOnProfile\":false}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("private")));
        var order = inOrder(guard, service);
        order.verify(guard).check(actor);
        order.verify(service).decide(actor, plan, new EventPlanDecisionRequest(7L, EventPlanDecision.ACCEPT, false));
    }

    private UserDetailsImpl principal(String roleName) {
        User account = new User();
        account.setId(actor); account.setUsername("plan-http-test");
        Role role = new Role(); role.setName(roleName); role.setPermissions(Set.of());
        account.setRoles(Set.of(role)); account.setPermissions(Set.of());
        return UserDetailsImpl.fromUser(account);
    }
}
