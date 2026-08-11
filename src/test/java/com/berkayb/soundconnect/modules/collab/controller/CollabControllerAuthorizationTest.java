package com.berkayb.soundconnect.modules.collab.controller;

import com.berkayb.soundconnect.modules.collab.service.CollabService;
import com.berkayb.soundconnect.auth.security.JwtAuthenticationFilter;
import com.berkayb.soundconnect.auth.security.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.beans.factory.annotation.Autowired;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = CollabController.class)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@Import(CollabControllerAuthorizationTest.MethodSecurityConfig.class)
class CollabControllerAuthorizationTest {
    @Autowired MockMvc mockMvc;
    @MockitoBean CollabService service;
    @MockitoBean JwtTokenProvider jwtTokenProvider;
    @MockitoBean JwtAuthenticationFilter jwtAuthenticationFilter;

    @Test
    @WithMockUser(roles = "LISTENER")
    void listenerCannotAccessBackstageCollabEndpoints() throws Exception {
        mockMvc.perform(get("/api/v1/collabs/actors/me"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityConfig {}
}
