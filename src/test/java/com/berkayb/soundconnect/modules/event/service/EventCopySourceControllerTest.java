package com.berkayb.soundconnect.modules.event.service;

import com.berkayb.soundconnect.auth.ratelimit.AuthRateLimitFilter;
import com.berkayb.soundconnect.auth.security.JwtAuthenticationFilter;
import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.event.controller.owner.EventCopySourceController;
import com.berkayb.soundconnect.modules.event.dto.request.EventCreateRequestDto;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.shared.config.SecurityConfig;
import com.berkayb.soundconnect.shared.security.*;
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

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(EventCopySourceController.class)
@Import({SecurityConfig.class, RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class,
        SecurityErrorResponseWriter.class})
class EventCopySourceControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean EventCopySourceService service;
    @MockitoBean JwtAuthenticationFilter jwt;
    @MockitoBean AuthRateLimitFilter authRateLimit;

    @BeforeEach
    void authenticationComesFromTheTestPrincipal() throws Exception {
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
    void guestAndNonVenueCannotReadCopySources() throws Exception {
        String path = "/api/v1/venue-owner/events/" + UUID.randomUUID() + "/copy-source";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).with(user(principal(UUID.randomUUID(), "ROLE_MUSICIAN"))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void usesAuthenticatedOwnerAndKeepsRawMediaReferencePrivate() throws Exception {
        UUID owner = UUID.randomUUID(), event = UUID.randomUUID(), poster = UUID.randomUUID();
        when(service.get(owner, event)).thenReturn(new EventCreateRequestDto("Konser", "Açıklama",
                LocalDate.of(2026, 10, 2), LocalTime.of(21, 0), null, poster.toString(),
                UUID.randomUUID(), null, null, "Akustik ekip"));
        mvc.perform(get("/api/v1/venue-owner/events/{id}/copy-source", event)
                        .with(user(principal(owner, "ROLE_VENUE"))))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$.data.posterImage").value(poster.toString()))
                .andExpect(jsonPath("$.data.title").value("Konser"));
        verify(service).get(owner, event);
    }

    private static UserDetailsImpl principal(UUID id, String roleName) {
        User actor = new User(); actor.setId(id); actor.setUsername("test-owner");
        Role role = new Role(); role.setName(roleName); role.setPermissions(Set.of());
        actor.setRoles(Set.of(role)); actor.setPermissions(Set.of());
        return UserDetailsImpl.fromUser(actor);
    }
}
