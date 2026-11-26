package com.berkayb.soundconnect.modules.analytics.report;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.analytics.AnalyticsRateGuard;
import com.berkayb.soundconnect.modules.user.service.MemberAnalyticsPreferenceService;
import com.berkayb.soundconnect.shared.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.util.UUID;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(properties = "app.analytics-report.live-enabled=true")
@ContextConfiguration(classes={LiveActivityController.class, GlobalExceptionHandler.class, LiveActivityControllerTest.Security.class})
class LiveActivityControllerTest {
    @Configuration
    @EnableMethodSecurity
    static class Security {
        @Bean SecurityFilterChain api(HttpSecurity http) throws Exception {
            return http.csrf(csrf -> csrf.disable())
                    .authorizeHttpRequests(auth -> auth.requestMatchers("/api/v1/analytics/live-activity/guest")
                            .permitAll().anyRequest().authenticated())
                    .exceptionHandling(errors -> errors.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                    .build();
        }
    }
    @Autowired MockMvc mvc;
    @MockitoBean LiveActivityStore store;
    @MockitoBean AnalyticsRateGuard limits;
    private final UUID id = UUID.randomUUID();
    private static final String POLICY = MemberAnalyticsPreferenceService.POLICY_VERSION;

    private MockHttpServletRequestBuilder visit(String audience, String policy) {
        String field = "guest".equals(audience) ? "installationId" : "analyticsUserId";
        return post("/api/v1/analytics/live-activity/" + audience).contentType("application/json")
                .content("{\"" + field + "\":\"" + id + "\",\"policyVersion\":\"" + policy + "\"}");
    }

    @Test void onlyAnAnonymousGuestWithCurrentConsentCanRegisterTheirInstallation() throws Exception {
        mvc.perform(visit("guest", POLICY)).andExpect(status().isNoContent());
        verify(store).guest(id);
        verify(limits).liveActivity(any());
        mvc.perform(visit("guest", POLICY).with(user("member"))).andExpect(status().isForbidden());
        mvc.perform(visit("guest", "2026-11-26-r2")).andExpect(status().isNoContent());
        verifyNoMoreInteractions(store, limits);
    }

    @Test void aMemberVisitRequiresAuthenticationAndExcludesPanelAccounts() throws Exception {
        mvc.perform(visit("member", POLICY)).andExpect(status().isUnauthorized());
        mvc.perform(visit("member", POLICY).with(user("admin").roles("MUSICIAN","ADMIN"))).andExpect(status().isForbidden());
        mvc.perform(visit("member", "2026-11-26-r2").with(user("member").roles("MUSICIAN"))).andExpect(status().isNoContent());
        verifyNoInteractions(store, limits);
    }

    @Test void theNormalMemberEndpointPassesOnlyTheAuthenticatedActorAndGrant() throws Exception {
        var actor = UserDetailsImpl.fromUser(User.builder().id(UUID.randomUUID()).sessionVersion(2).build());
        var auth = new UsernamePasswordAuthenticationToken(actor,null,List.of(new SimpleGrantedAuthority("ROLE_MUSICIAN")));
        mvc.perform(visit("member", POLICY).with(authentication(auth))).andExpect(status().isNoContent());
        verify(limits).liveActivity(any());
        verify(store).member(actor,id);
    }

    @Test void theGuestPathRejectsAnAuthorizationHeaderOrMissingId() throws Exception {
        mvc.perform(visit("guest", POLICY).header("Authorization", "unused")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/analytics/live-activity/guest").contentType("application/json")
                        .content("{\"policyVersion\":\"" + POLICY + "\"}")).andExpect(status().isBadRequest());
        verifyNoInteractions(store, limits);
    }
}
