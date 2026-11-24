package com.berkayb.soundconnect.modules.venue.suggestion;

import com.berkayb.soundconnect.auth.ratelimit.AuthRateLimitFilter;
import com.berkayb.soundconnect.auth.security.JwtAuthenticationFilter;
import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.shared.config.SecurityConfig;
import com.berkayb.soundconnect.shared.response.PageResponse;
import com.berkayb.soundconnect.shared.security.RestAccessDeniedHandler;
import com.berkayb.soundconnect.shared.security.RestAuthenticationEntryPoint;
import com.berkayb.soundconnect.shared.security.SecurityErrorResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import java.util.List;
import java.util.UUID;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({VenueSuggestionController.class, VenueSuggestionAdminController.class})
@Import({SecurityConfig.class, RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class, SecurityErrorResponseWriter.class})
class VenueSuggestionSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean VenueSuggestionService service;
    @MockitoBean VenueSuggestionAdminQuery suggestions;
    @MockitoBean JwtAuthenticationFilter jwt;
    @MockitoBean AuthRateLimitFilter rateLimit;
    private static final String ADMIN = "/api/v1/admin/venue-suggestions";
    private final UserDetailsImpl actor = UserDetailsImpl.fromUser(User.builder().id(UUID.randomUUID()).build());

    private RequestPostProcessor withAuthorities(String... authorities) {
        var permissions = java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList();
        return authentication(UsernamePasswordAuthenticationToken.authenticated(actor, null, permissions));
    }

    @BeforeEach void noCredentials() throws Exception {
        doAnswer(invocation -> {
            FilterChain chain = invocation.getArgument(2);
            chain.doFilter(invocation.getArgument(0), invocation.getArgument(1)); return null;
        }).when(jwt).doFilter(any(ServletRequest.class), any(ServletResponse.class), any(FilterChain.class));
        doAnswer(invocation -> {
            FilterChain chain = invocation.getArgument(2);
            chain.doFilter(invocation.getArgument(0), invocation.getArgument(1)); return null;
        }).when(rateLimit).doFilter(any(ServletRequest.class), any(ServletResponse.class), any(FilterChain.class));
    }
    @Test void exactAnonymousSuggestionPostIsAcceptedWithoutOpeningOtherMutations() throws Exception {
        String body = "{\"requestId\":\"" + UUID.randomUUID() + "\",\"venueName\":\"Mekan\",\"cityId\":\""
                + UUID.randomUUID() + "\",\"districtId\":\"" + UUID.randomUUID() + "\",\"liveMusic\":\"UNKNOWN\"}";
        mvc.perform(post("/api/v1/venue-suggestions").contentType("application/json").content(body))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.accepted").value(true));
        verify(service).accept(any());
        mvc.perform(post("/api/v1/venue-suggestions/other").contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/venues").contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/v1/venue-suggestions")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/venue-suggestions")).andExpect(status().isUnauthorized());
        verifyNoMoreInteractions(service);
    }

    @Test void privateVenueListRequiresBothGrantsAndExcludesListenerAccounts() throws Exception {
        mvc.perform(get(ADMIN)).andExpect(status().isUnauthorized());
        mvc.perform(get(ADMIN).with(withAuthorities("ROLE_USER"))).andExpect(status().isForbidden());
        mvc.perform(get(ADMIN).with(withAuthorities("ADMIN_PANEL_ACCESS"))).andExpect(status().isForbidden());
        mvc.perform(get(ADMIN).with(withAuthorities("MANAGE_VENUES"))).andExpect(status().isForbidden());
        mvc.perform(get(ADMIN).with(withAuthorities("ADMIN_PANEL_ACCESS", "MANAGE_VENUES", "ROLE_LISTENER")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(suggestions);
    }

    @Test void authorizedGetReturnsOnlyFourSuggestionFields() throws Exception {
        var record = new VenueSuggestionAdminQuery.Row("Sahne", "Ankara", "Çankaya", VenueSuggestionRequest.LiveMusic.UNKNOWN);
        when(suggestions.list(actor, 0, 20)).thenReturn(
                PageResponse.from(new PageImpl<>(List.of(record), PageRequest.of(0, 20), 1)));
        mvc.perform(get(ADMIN).with(withAuthorities("ADMIN_PANEL_ACCESS", "MANAGE_VENUES")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.data.content[0].*", hasSize(4)))
                .andExpect(jsonPath("$.data.content[0].venueName").value("Sahne"))
                .andExpect(jsonPath("$.data.content[0].cityName").value("Ankara"))
                .andExpect(jsonPath("$.data.content[0].districtName").value("Çankaya"))
                .andExpect(jsonPath("$.data.content[0].liveMusic").value("UNKNOWN"));
        verify(suggestions).list(actor, 0, 20);
        verifyNoInteractions(service);
    }
}
