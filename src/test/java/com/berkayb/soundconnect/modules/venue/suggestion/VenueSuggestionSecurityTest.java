package com.berkayb.soundconnect.modules.venue.suggestion;

import com.berkayb.soundconnect.auth.ratelimit.AuthRateLimitFilter;
import com.berkayb.soundconnect.auth.security.JwtAuthenticationFilter;
import com.berkayb.soundconnect.shared.config.SecurityConfig;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(VenueSuggestionController.class)
@Import({SecurityConfig.class, RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class, SecurityErrorResponseWriter.class})
class VenueSuggestionSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean VenueSuggestionService service;
    @MockitoBean JwtAuthenticationFilter jwt;
    @MockitoBean AuthRateLimitFilter rateLimit;
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
}
