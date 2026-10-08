package com.berkayb.soundconnect.modules.event.management;

import com.berkayb.soundconnect.auth.ratelimit.AuthRateLimitFilter;
import com.berkayb.soundconnect.auth.security.JwtAuthenticationFilter;
import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.user.entity.User;
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
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(EventManagementController.class)
@Import({SecurityConfig.class, RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class, SecurityErrorResponseWriter.class})
class EventManagementControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean EventManagementService service;
    @MockitoBean JwtAuthenticationFilter jwt;
    @MockitoBean AuthRateLimitFilter authRateLimit;
    private final UUID owner = UUID.randomUUID(), venue = UUID.randomUUID();
    private final String base = "/api/v1/venue-owner/events/venue/" + venue;
    private final String asOf = "2026-09-21T09:00:00Z";

    @BeforeEach void authenticationComesFromTestPrincipal() throws Exception {
        doAnswer(invocation -> {
            invocation.<FilterChain>getArgument(2).doFilter(invocation.getArgument(0), invocation.getArgument(1)); return null;
        }).when(jwt).doFilter(any(ServletRequest.class), any(ServletResponse.class), any(FilterChain.class));
        doAnswer(invocation -> {
            invocation.<FilterChain>getArgument(2).doFilter(invocation.getArgument(0), invocation.getArgument(1)); return null;
        }).when(authRateLimit).doFilter(any(ServletRequest.class), any(ServletResponse.class), any(FilterChain.class));
    }

    @Test void bothEndpointsRefuseAnonymousAndNonVenuePrincipals() throws Exception {
        for (String path : List.of(base + "/management", base + "/history?asOf=" + asOf)) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).with(user(principal("ROLE_MUSICIAN")))).andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);
    }

    @Test void managementUsesAuthenticatedOwnerAndPrivateEnvelope() throws Exception {
        when(service.management(owner, venue)).thenReturn(new EventManagementResponse(List.of(), 120_000L, Instant.parse(asOf)));
        mvc.perform(get(base + "/management").with(user(principal("ROLE_VENUE"))))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data.upcomingEvents").isArray())
                .andExpect(jsonPath("$.data.pastCount").value(120_000)).andExpect(jsonPath("$.data.historyAsOf").value(asOf));
        verify(service).management(owner, venue);
    }

    @Test void historyDefaultsToTwentyAndHasNullableContinuation() throws Exception {
        when(service.history(owner, venue, asOf, null, 20)).thenReturn(new EventHistoryPage(List.of(), null, false));
        mvc.perform(get(base + "/history").param("asOf", asOf).with(user(principal("ROLE_VENUE"))))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$.data.items").isArray()).andExpect(jsonPath("$.data.hasNext").value(false))
                .andExpect(jsonPath("$.data.nextCursor").isEmpty());
        verify(service).history(owner, venue, asOf, null, 20);
    }

    @Test void historyForwardsOpaqueCursorWithoutChangingIt() throws Exception {
        when(service.history(owner, venue, asOf, "opaque_cursor-1", 20)).thenReturn(new EventHistoryPage(List.of(), "opaque_cursor-2", true));
        mvc.perform(get(base + "/history").param("asOf", asOf).param("cursor", "opaque_cursor-1")
                        .with(user(principal("ROLE_VENUE"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.nextCursor").value("opaque_cursor-2"));
        verify(service).history(owner, venue, asOf, "opaque_cursor-1", 20);
    }

    @Test void historyRequiresBoundaryAndNumericSize() throws Exception {
        mvc.perform(get(base + "/history").with(user(principal("ROLE_VENUE")))).andExpect(status().isBadRequest());
        mvc.perform(get(base + "/history").param("asOf", asOf).param("size", "many")
                .with(user(principal("ROLE_VENUE")))).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    private UserDetailsImpl principal(String roleName) {
        User actor = new User(); actor.setId(owner); actor.setUsername("history-owner");
        Role role = new Role(); role.setName(roleName); role.setPermissions(Set.of());
        actor.setRoles(Set.of(role)); actor.setPermissions(Set.of());
        return UserDetailsImpl.fromUser(actor);
    }
}
