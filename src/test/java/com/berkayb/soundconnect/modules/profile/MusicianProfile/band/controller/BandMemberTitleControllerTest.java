package com.berkayb.soundconnect.modules.profile.MusicianProfile.band.controller;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.request.BandMemberTitleUpdateDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.response.BandMemberResponseDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.service.BandService;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = BandUserController.class, excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE, classes = com.berkayb.soundconnect.auth.security.JwtAuthenticationFilter.class))
@AutoConfigureMockMvc(addFilters = false)
@Import(BandMemberTitleControllerTest.MethodSecurity.class)
class BandMemberTitleControllerTest {
    @TestConfiguration @EnableMethodSecurity static class MethodSecurity {}
    @Autowired MockMvc mvc;
    @MockitoBean BandService service;
    final UUID bandId = UUID.randomUUID(), targetId = UUID.randomUUID();
    String path() { return "/api/v1/user/bands/" + bandId + "/members/" + targetId + "/title"; }
    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @Test void endpointUsesAuthenticatedActorAndReturnsUpdatedMember() throws Exception {
        UUID actor = authenticate("ROLE_MUSICIAN", UserStatus.ACTIVE, true);
        when(service.updateMemberTitle(bandId, actor, targetId, new BandMemberTitleUpdateDto("Davul", 3L)))
                .thenReturn(new BandMemberResponseDto(targetId, "aedrum", null, "MEMBER", "ACTIVE", "Davul", 4));
        mvc.perform(patch(path()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberTitle\":\"Davul\",\"expectedTitleVersion\":3}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.data.memberTitle").value("Davul"))
                .andExpect(jsonPath("$.data.titleVersion").value(4))
                .andExpect(jsonPath("$.data.userId").value(targetId.toString()));
    }

    @Test void guestCannotWrite() throws Exception {
        mvc.perform(patch(path()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"memberTitle\":null,\"expectedTitleVersion\":0}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @ParameterizedTest @ValueSource(strings = {"ROLE_VENUE", "ROLE_LISTENER", "ROLE_STUDIO"})
    void otherRolesCannotWrite(String role) throws Exception {
        authenticate(role, UserStatus.ACTIVE, true);
        mvc.perform(patch(path()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"memberTitle\":\"Davul\",\"expectedTitleVersion\":0}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test void disabledMusicianCannotWrite() throws Exception {
        authenticate("ROLE_MUSICIAN", UserStatus.INACTIVE, true);
        mvc.perform(patch(path()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"memberTitle\":\"Davul\",\"expectedTitleVersion\":0}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test void unverifiedMusicianCannotWrite() throws Exception {
        authenticate("ROLE_MUSICIAN", UserStatus.ACTIVE, false);
        mvc.perform(patch(path()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"memberTitle\":\"Davul\",\"expectedTitleVersion\":0}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @ParameterizedTest @ValueSource(strings = {
            "{\"expectedTitleVersion\":0}",
            "{}", "{\"memberTitle\":\"Davul\"}", "{\"memberTitle\":\"Davul\",\"expectedTitleVersion\":-1}",
            "{\"memberTitle\":\"Davul\",\"expectedTitleVersion\":0.5}",
            "{\"memberTitle\":\"Davul\",\"expectedTitleVersion\":\"0\"}",
            "{\"memberTitle\":42,\"expectedTitleVersion\":0}",
            "{\"memberTitle\":\"123456789012345678901\",\"expectedTitleVersion\":0}",
            "{\"memberTitle\":\"a\\nb\",\"expectedTitleVersion\":0}"
    })
    void invalidDtoNeverReachesService(String body) throws Exception {
        authenticate("ROLE_MUSICIAN", UserStatus.ACTIVE, true);
        mvc.perform(patch(path()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void staleVersionReturnsConflictContract() throws Exception {
        authenticate("ROLE_MUSICIAN", UserStatus.ACTIVE, true);
        when(service.updateMemberTitle(any(), any(), any(), any()))
                .thenThrow(new SoundConnectException(ErrorType.BAND_MEMBER_TITLE_VERSION_CONFLICT));
        mvc.perform(patch(path()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"memberTitle\":\"Davul\",\"expectedTitleVersion\":0}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(9215));
    }

    @Test void removalPassesTheOriginatingRosterVersionAndAuthenticatedActor() throws Exception {
        UUID actor = authenticate("ROLE_MUSICIAN", UserStatus.ACTIVE, true);

        mvc.perform(delete("/api/v1/user/bands/" + bandId + "/remove/" + targetId)
                        .param("expectedTitleVersion", "3"))
                .andExpect(status().isOk());

        verify(service).removeMember(bandId, actor, targetId, 3L);
    }

    @ParameterizedTest @ValueSource(strings = {"", "-1", "2"})
    void missingOrStaleRemovalVersionReturnsReadableConflict(String version) throws Exception {
        UUID actor = authenticate("ROLE_MUSICIAN", UserStatus.ACTIVE, true);
        Long expected = version.isEmpty() ? null : Long.valueOf(version);
        doThrow(new SoundConnectException(ErrorType.BAND_MEMBER_VERSION_CONFLICT))
                .when(service).removeMember(bandId, actor, targetId, expected);
        var request = delete("/api/v1/user/bands/" + bandId + "/remove/" + targetId);
        if (!version.isEmpty()) request.param("expectedTitleVersion", version);

        mvc.perform(request).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(9221));

        verify(service).removeMember(bandId, actor, targetId, expected);
    }

    @ParameterizedTest @ValueSource(strings = {"PATCH", "PUT"})
    void bothLeaveRoutesRequireTheOriginatingRosterVersion(String method) throws Exception {
        UUID actor = authenticate("ROLE_MUSICIAN", UserStatus.ACTIVE, true);
        String leavePath = "/api/v1/user/bands/" + bandId + "/leave";
        var request = method.equals("PATCH") ? patch(leavePath) : put(leavePath);
        mvc.perform(request.param("expectedTitleVersion", "5")).andExpect(status().isOk());
        verify(service).leaveBand(bandId, actor, 5L);
    }

    @ParameterizedTest @ValueSource(strings = {"PATCH", "PUT"})
    void legacyUnversionedLeaveCannotBypassTheMembershipFence(String method) throws Exception {
        UUID actor = authenticate("ROLE_MUSICIAN", UserStatus.ACTIVE, true);
        doThrow(new SoundConnectException(ErrorType.BAND_MEMBER_VERSION_CONFLICT))
                .when(service).leaveBand(bandId, actor, null);
        String leavePath = "/api/v1/user/bands/" + bandId + "/leave";
        var request = method.equals("PATCH") ? patch(leavePath) : put(leavePath);
        mvc.perform(request).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(9221));
    }

    private UUID authenticate(String role, UserStatus status, boolean verified) {
        UUID id = UUID.randomUUID();
        User user = User.builder().id(id).username("owner").password("unused").email("owner@example.com")
                .emailVerified(verified).status(status).roles(Set.of(Role.builder().name(role).build())).build();
        var principal = UserDetailsImpl.fromUser(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        return id;
    }
}
