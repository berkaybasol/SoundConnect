package com.berkayb.soundconnect.modules.collab.controller;

import com.berkayb.soundconnect.auth.security.JwtAuthenticationFilter;
import com.berkayb.soundconnect.auth.security.JwtTokenProvider;
import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.collab.controller.admin.CollabReportAdminController;
import com.berkayb.soundconnect.modules.collab.dto.response.CollabReportAdminResponse;
import com.berkayb.soundconnect.modules.collab.service.CollabReportModerationService;
import com.berkayb.soundconnect.shared.response.PageResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = CollabReportAdminController.class)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@Import(CollabReportAdminControllerAuthorizationTest.MethodSecurityConfig.class)
class CollabReportAdminControllerAuthorizationTest {
    @Autowired MockMvc mockMvc;
    @MockitoBean CollabReportModerationService service;
    @MockitoBean JwtTokenProvider jwtTokenProvider;
    @MockitoBean JwtAuthenticationFilter jwtAuthenticationFilter;

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminRoleWithoutExplicitPermissionCannotReadReports() throws Exception {
        mockMvc.perform(get("/api/v1/admin/collab/reports"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    @WithMockUser(authorities = "MANAGE_COLLAB_REPORTS")
    void permissionAllowsReadingModerationQueue() throws Exception {
        when(service.list(isNull(), isNull(), anyInt(), anyInt())).thenReturn(
                new PageResponse<CollabReportAdminResponse>(List.of(), 0, 20, 0, 0, true, true));

        mockMvc.perform(get("/api/v1/admin/collab/reports"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Collab raporları listelendi."))
                .andExpect(jsonPath("$.data.content").isArray());

        verify(service).list(null, null, 0, 20);
    }

    @Test
    void reviewResponseUsesUtf8TurkishMessage() throws Exception {
        UUID adminId = UUID.randomUUID();
        UUID reportId = UUID.randomUUID();
        UserDetailsImpl principal = mock(UserDetailsImpl.class);
        when(principal.getId()).thenReturn(adminId);
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                principal,
                null,
                List.of(new SimpleGrantedAuthority("MANAGE_COLLAB_REPORTS")));
        when(service.review(eq(adminId), eq(reportId), any())).thenReturn(mock(CollabReportAdminResponse.class));

        SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            mockMvc.perform(post("/api/v1/admin/collab/reports/{reportId}/review", reportId)
                            .contentType("application/json")
                            .content("""
                                    {
                                      "decision": "DISMISS",
                                      "expectedVersion": 0,
                                      "resolutionNote": "İhlal tespit edilmedi."
                                    }
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("Collab raporu sonuçlandırıldı."));
        } finally {
            SecurityContextHolder.clearContext();
        }

        verify(service).review(eq(adminId), eq(reportId), any());
    }

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityConfig {}
}
