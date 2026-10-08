package com.berkayb.soundconnect.modules.admin.health;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest
@ContextConfiguration(classes = {SystemHealthController.class, SystemHealthControllerSecurityTest.Security.class})
class SystemHealthControllerSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean SystemHealthService service;
    @BeforeEach void clearStartupLifecycleInvocation() {
        // Readiness is infrastructure; authorization assertions still reject all request interactions.
        clearInvocations(service);
    }
    @Configuration @EnableWebSecurity @EnableMethodSecurity
    static class Security {
        @Bean SecurityFilterChain chain(HttpSecurity http) throws Exception {
            return http.csrf(csrf -> csrf.disable()).authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .exceptionHandling(ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))).build();
        }
    }

    @Test void anonymousAndRoleOnlyAccountsCannotReadHealth() throws Exception {
        mvc.perform(get("/api/v1/admin/system-health")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/system-health").with(user("regular").roles("USER"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/system-health").with(user("role-only").roles("ADMIN"))).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test void existingAdminPermissionCanReadUnknownSnapshotWithoutClaimingHealthy() throws Exception {
        when(service.snapshot()).thenReturn(new SystemHealthSnapshot(SystemHealthSnapshot.Status.UNKNOWN, Instant.now(), 15, 60, List.of()));
        mvc.perform(get("/api/v1/admin/system-health").with(user("operator").authorities(new SimpleGrantedAuthority("ADMIN_PANEL_ACCESS"))))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data.status").value("UNKNOWN"));
        verify(service).snapshot(); verifyNoMoreInteractions(service);
    }
}
