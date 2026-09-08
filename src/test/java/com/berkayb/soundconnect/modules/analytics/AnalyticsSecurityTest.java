package com.berkayb.soundconnect.modules.analytics;

import com.berkayb.soundconnect.auth.ratelimit.AuthRateLimitFilter;
import com.berkayb.soundconnect.auth.security.JwtAuthenticationFilter;
import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.shared.config.SecurityConfig;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.security.RestAccessDeniedHandler;
import com.berkayb.soundconnect.shared.security.RestAuthenticationEntryPoint;
import com.berkayb.soundconnect.shared.security.SecurityErrorResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.UUID;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real route policy and body filter, with database/JWT verification isolated. */
@WebMvcTest(AnalyticsController.class)
@Import({SecurityConfig.class, AnalyticsConfiguration.class, RestAuthenticationEntryPoint.class,
        RestAccessDeniedHandler.class, SecurityErrorResponseWriter.class})
class AnalyticsSecurityTest {
    private static final String COLLECT = "/api/v1/analytics/observations";
    private static final UUID VENUE = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID EVENT = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID OWNER = UUID.fromString("30000000-0000-4000-8000-000000000001");
    @Autowired MockMvc mvc;
    @Autowired FilterRegistrationBean<AnalyticsSubmissionFilter> submissionRegistration;
    @MockitoBean AnalyticsService service;
    @MockitoBean AnalyticsRateGuard guard;
    @MockitoBean JwtAuthenticationFilter jwt;
    @MockitoBean AuthRateLimitFilter authRateLimit;

    @BeforeEach void applicationFiltersPassThrough() throws Exception {
        doAnswer(invocation -> {
            FilterChain chain = invocation.getArgument(2);
            chain.doFilter(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(jwt).doFilter(any(ServletRequest.class), any(ServletResponse.class), any(FilterChain.class));
        doAnswer(invocation -> {
            FilterChain chain = invocation.getArgument(2);
            chain.doFilter(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(authRateLimit).doFilter(any(ServletRequest.class), any(ServletResponse.class), any(FilterChain.class));
    }

    @Test void onlyExactAnonymousCollectorPostIsPublic() throws Exception {
        mvc.perform(post(COLLECT).contentType("application/json").content(body()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        verify(service).observe(isNull(), any());
        for (String path : List.of(COLLECT + "/", COLLECT + "/other", COLLECT + "-other")) {
            mvc.perform(post(path).contentType("application/json").content(body())).andExpect(status().isUnauthorized());
        }
        mvc.perform(get(COLLECT)).andExpect(status().isUnauthorized());
        mvc.perform(put(COLLECT)).andExpect(status().isUnauthorized());
        mvc.perform(delete(COLLECT)).andExpect(status().isUnauthorized());
        verifyNoMoreInteractions(service);
        assertThat(submissionRegistration.getOrder()).isEqualTo(-89);
    }

    @ParameterizedTest @ValueSource(strings = {"Bearer invalid", "Bearer expired-test", "Basic invalid", ""})
    void suppliedButUnauthenticatedCredentialsNeverDowngradeToGuest(String authorization) throws Exception {
        mvc.perform(post(COLLECT).header("Authorization", authorization)
                        .contentType("application/json").content(body()))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test void authenticatedCollectionUsesPrincipalNotInstallationOrQueryIdentity() throws Exception {
        mvc.perform(post(COLLECT).with(viewer(OWNER)).header("Authorization", "Bearer test-principal")
                        .queryParam("userId", UUID.randomUUID().toString())
                        .contentType("application/json").content(body()))
                .andExpect(status().isOk());
        verify(service).observe(eq(OWNER), any());
    }

    @Test void allPrivateAnalyticsReadsRemainOutsidePublicVenueAndEventNamespaces() throws Exception {
        for (String path : List.of(summaryPath(), eventsPath(), eventsPath() + "/" + EVENT)) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(service, guard);
    }

    @Test void authenticatedPrivateReadsForwardExactActorAndAreNeverCacheable() throws Exception {
        mvc.perform(get(summaryPath()).with(viewer(OWNER))).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store"));
        mvc.perform(get(eventsPath()).with(viewer(OWNER)).param("page", "1000").param("size", "50"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "private, no-store"));
        mvc.perform(get(eventsPath() + "/" + EVENT).with(viewer(OWNER)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "private, no-store"));
        verify(service).summary(OWNER, VENUE, null, 30);
        verify(service).events(OWNER, VENUE, 30, 1000, 50, AnalyticsResponse.EventSort.DATE);
        verify(service).summary(OWNER, VENUE, EVENT, 30);
        verify(guard, times(3)).read(any(), eq(OWNER));
    }

    @ParameterizedTest @EnumSource(AnalyticsResponse.EventSort.class)
    void supportedSortsReachOwnerServiceAndRoundTripInPrivateResponse(AnalyticsResponse.EventSort sort) throws Exception {
        when(service.events(OWNER, VENUE, 7, 2, 3, sort)).thenReturn(new AnalyticsResponse.EventPage(List.of(),2,3,6,2,false,sort));
        mvc.perform(get(eventsPath()).with(viewer(OWNER)).param("days","7").param("page","2").param("size","3").param("sort",sort.name()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","private, no-store"))
                .andExpect(jsonPath("$.data.sort").value(sort.name())).andExpect(jsonPath("$.data.page").value(2));
        verify(service).events(OWNER,VENUE,7,2,3,sort);
        verify(guard).read(any(),eq(OWNER));
    }

    @ParameterizedTest @ValueSource(strings={"reach","UNKNOWN","impressions","0","REACH DESC; SELECT 1"})
    void malformedSortCannotReachPrivateService(String sort) throws Exception {
        mvc.perform(get(eventsPath()).with(viewer(OWNER)).param("sort",sort)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void dailyAndUnavailableComparisonPreserveNullCoverageAndCalendarMetadata() throws Exception {
        LocalDate today=LocalDate.of(2026,9,8);
        var comparison=new AnalyticsResponse.PeriodComparison(AnalyticsResponse.ComparisonStatus.INSUFFICIENT_HISTORY,
                today.minusDays(7),today.minusDays(1),today.minusDays(14),today.minusDays(8),null,null);
        var daily=today.minusDays(6).datesUntil(today.plusDays(1)).map(day -> new AnalyticsResponse.DailyPoint(day,
                day.equals(today)?AnalyticsResponse.Metrics.ZERO:null,day.equals(today))).toList();
        when(service.summary(OWNER,VENUE,null,7)).thenReturn(new AnalyticsResponse.Summary(VENUE,null,today.minusDays(6),today,7,
                "Europe/Istanbul",Instant.parse("2026-09-08T12:00:00Z"),Instant.parse("2026-09-08T12:01:00Z"),AnalyticsResponse.Metrics.ZERO,daily,comparison));
        mvc.perform(get(summaryPath()).with(viewer(OWNER)).param("days","7"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","private, no-store"))
                .andExpect(jsonPath("$.data.eventId").doesNotExist()).andExpect(jsonPath("$.data.daily.length()").value(7))
                .andExpect(jsonPath("$.data.daily[0].date").value("2026-09-02"))
                .andExpect(jsonPath("$.data.daily[0].metrics").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.daily[0].partial").value(false))
                .andExpect(jsonPath("$.data.daily[6].metrics.impressions").value(0)).andExpect(jsonPath("$.data.daily[6].partial").value(true))
                .andExpect(jsonPath("$.data.comparison.status").value("INSUFFICIENT_HISTORY"))
                .andExpect(jsonPath("$.data.comparison.currentFromDate").value("2026-09-01"))
                .andExpect(jsonPath("$.data.comparison.previousFromDate").value("2026-08-25"))
                .andExpect(jsonPath("$.data.comparison.currentMetrics").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.comparison.previousMetrics").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test void sortedForeignVenueStillReturnsNotFoundWithoutCountLeak() throws Exception {
        when(service.events(OWNER,VENUE,30,0,20,AnalyticsResponse.EventSort.REACH))
                .thenThrow(new SoundConnectException(ErrorType.VENUE_NOT_FOUND));
        mvc.perform(get(eventsPath()).with(viewer(OWNER)).param("sort","REACH").param("ownerUserId",UUID.randomUUID().toString()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.data").doesNotExist());
        verify(service).events(OWNER,VENUE,30,0,20,AnalyticsResponse.EventSort.REACH);
    }

    @Test void foreignOwnerNotFoundDoesNotLeakCountsOrAlterAuthenticatedActor() throws Exception {
        when(service.summary(OWNER, VENUE, null, 30)).thenThrow(new SoundConnectException(ErrorType.VENUE_NOT_FOUND));
        mvc.perform(get(summaryPath()).with(viewer(OWNER)).param("ownerUserId", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.data").doesNotExist());
        verify(service).summary(OWNER, VENUE, null, 30);
    }

    @ParameterizedTest @CsvSource({"page,-1", "page,1001", "page,2147483648", "page,nope", "size,0", "size,51", "size,nope", "days,nope", "days,2147483648"})
    void unboundedOrMalformedReadQueriesNeverReachService(String key, String value) throws Exception {
        mvc.perform(get(eventsPath()).with(viewer(OWNER)).param(key, value)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void invalidPathIdentifiersFailWithoutRead() throws Exception {
        mvc.perform(get("/api/v1/venue-analytics/not-a-uuid").with(viewer(OWNER))).andExpect(status().isBadRequest());
        mvc.perform(get(eventsPath() + "/not-a-uuid").with(viewer(OWNER))).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    private static String summaryPath() { return "/api/v1/venue-analytics/" + VENUE; }
    private static String eventsPath() { return summaryPath() + "/events"; }
    private static String body() { return "{\"clientId\":\"" + UUID.randomUUID() + "\",\"observations\":[{\"id\":\"" + UUID.randomUUID()
            + "\",\"type\":\"EVENT_IMPRESSION\",\"eventId\":\"" + EVENT + "\",\"observedAt\":\"2026-09-08T12:00:00Z\"}]}"; }
    private static RequestPostProcessor viewer(UUID id) {
        User user = User.builder().username("test-viewer").build();
        user.setId(id);
        return authentication(UsernamePasswordAuthenticationToken.authenticated(new UserDetailsImpl(user), null,
                List.of(new SimpleGrantedAuthority("ROLE_VENUE"))));
    }
}
