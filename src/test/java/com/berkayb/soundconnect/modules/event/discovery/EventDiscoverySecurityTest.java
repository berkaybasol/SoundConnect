package com.berkayb.soundconnect.modules.event.discovery;

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

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(EventDiscoveryController.class)
@Import({SecurityConfig.class, RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class,
        SecurityErrorResponseWriter.class})
class EventDiscoverySecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean EventDiscoveryService service;
    @MockitoBean JwtAuthenticationFilter jwt;
    @MockitoBean AuthRateLimitFilter rateLimit;

    @BeforeEach
    void noAuthenticationCredentials() throws Exception {
        doAnswer(invocation -> {
            FilterChain chain = invocation.getArgument(2);
            chain.doFilter(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(jwt).doFilter(any(ServletRequest.class), any(ServletResponse.class), any(FilterChain.class));
        doAnswer(invocation -> {
            FilterChain chain = invocation.getArgument(2);
            chain.doFilter(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(rateLimit).doFilter(any(ServletRequest.class), any(ServletResponse.class), any(FilterChain.class));
    }

    @Test
    void guestsCanDiscoverEventsWithoutSessionOrJwt() throws Exception {
        UUID city = UUID.randomUUID();
        when(service.discover(null, city, null, null, 0, 20))
                .thenReturn(new EventDiscoveryPage(List.of(), 0, 20, 0, 0, true));
        mvc.perform(get("/api/v1/events/discovery").param("cityId", city.toString()))
                .andExpect(status().isOk());
        verify(service).discover(null, city, null, null, 0, 20);
    }

    @Test
    void invalidGuestRequestsGetValidationErrorsNotLoginRequests() throws Exception {
        mvc.perform(get("/api/v1/events/discovery")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void publicReadRouteDoesNotOpenAnonymousWrites() throws Exception {
        mvc.perform(post("/api/v1/events/discovery")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/v1/events/discovery")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
}
