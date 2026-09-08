package com.berkayb.soundconnect.modules.profile.MusicianProfile.band.controller;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.response.BandPendingInvitationResponseDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.service.BandService;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.response.PageResponse;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = BandUserController.class, excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE, classes = com.berkayb.soundconnect.auth.security.JwtAuthenticationFilter.class))
@AutoConfigureMockMvc(addFilters = false)
@Import(BandPendingInvitationsControllerTest.MethodSecurity.class)
class BandPendingInvitationsControllerTest {
    @TestConfiguration @EnableMethodSecurity static class MethodSecurity {}
    @Autowired MockMvc mvc;
    @MockitoBean BandService service;
    final UUID bandId = UUID.randomUUID();
    String path() { return "/api/v1/user/bands/" + bandId + "/invitations/pending"; }
    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @Test void receivedEndpointIgnoresOtherUserIdAndKeepsPrivateNoStore() throws Exception {
        UUID actor = authenticate("ROLE_MUSICIAN", UserStatus.ACTIVE, true);
        var row = new com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.response.BandReceivedInvitationResponseDto(bandId,"Şahbaz",null,"PENDING",UUID.randomUUID());
        when(service.getReceivedInvitations(actor,0,20)).thenReturn(new PageResponse<>(List.of(row),0,20,1,1,true,true));
        mvc.perform(get("/api/v1/user/bands/invitations/received").param("userId",UUID.randomUUID().toString()))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control",containsString("no-store")))
            .andExpect(jsonPath("$.data.content[0].bandId").value(bandId.toString()))
            .andExpect(jsonPath("$.data.content[0].bandName").value("Şahbaz"))
            .andExpect(jsonPath("$.data.content[0].profilePictureMediaId").doesNotExist());
        verify(service).getReceivedInvitations(actor,0,20);
    }

    @Test void receivedEndpointRejectsGuest() throws Exception {
        mvc.perform(get("/api/v1/user/bands/invitations/received")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test void currentReceivedInvitationUsesAuthenticatedRecipientAndPrivateResponse() throws Exception {
        UUID actor = authenticate("ROLE_MUSICIAN", UserStatus.ACTIVE, true), invitationId = UUID.randomUUID();
        var row = new com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.response.BandReceivedInvitationResponseDto(
            bandId,"Şahbaz",null,"PENDING",invitationId);
        when(service.getCurrentReceivedInvitation(bandId,actor)).thenReturn(row);
        mvc.perform(get("/api/v1/user/bands/"+bandId+"/invitations/received/current")
                .param("userId",UUID.randomUUID().toString()))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control",containsString("no-store")))
            .andExpect(jsonPath("$.data.invitationId").value(invitationId.toString()));
        verify(service).getCurrentReceivedInvitation(bandId,actor);
    }

    @ParameterizedTest @ValueSource(strings={"accept","reject"})
    void decisionCarriesExactInvitationIdentityAndAuthenticatedActor(String decision) throws Exception {
        UUID actor = authenticate("ROLE_MUSICIAN",UserStatus.ACTIVE,true), invitationId = UUID.randomUUID();
        mvc.perform(post("/api/v1/user/bands/"+bandId+"/"+decision)
            .param("invitationId",invitationId.toString()).param("userId",UUID.randomUUID().toString()))
            .andExpect(status().isOk());
        if (decision.equals("accept")) verify(service).acceptInvite(bandId,actor,invitationId);
        else verify(service).rejectInvite(bandId,actor,invitationId);
    }

    @ParameterizedTest @ValueSource(strings={"accept","reject"})
    void legacyDecisionWithoutIdentityReceivesReadableStaleConflict(String decision) throws Exception {
        UUID actor = authenticate("ROLE_MUSICIAN",UserStatus.ACTIVE,true);
        if (decision.equals("accept")) doThrow(new SoundConnectException(ErrorType.BAND_INVITE_STALE))
            .when(service).acceptInvite(bandId,actor,null);
        else doThrow(new SoundConnectException(ErrorType.BAND_INVITE_STALE)).when(service).rejectInvite(bandId,actor,null);
        mvc.perform(post("/api/v1/user/bands/"+bandId+"/"+decision))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(9220));
    }

    @ParameterizedTest @ValueSource(strings={"ROLE_VENUE","ROLE_LISTENER","ROLE_STUDIO"})
    void receivedEndpointRejectsOtherRoles(String role) throws Exception {
        authenticate(role,UserStatus.ACTIVE,true);
        mvc.perform(get("/api/v1/user/bands/invitations/received")).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test void endpointUsesAuthenticatedActorAndDefaultPaginationWithPrivateNoStore() throws Exception {
        UUID actor = authenticate("ROLE_MUSICIAN", UserStatus.ACTIVE, true);
        var row = new BandPendingInvitationResponseDto(UUID.randomUUID(), "aedrum", "avatar.jpg", "PENDING");
        when(service.getPendingInvitations(bandId, actor, 0, 20))
                .thenReturn(new PageResponse<>(List.of(row), 0, 20, 1, 1, true, true));
        mvc.perform(get(path()).param("requesterId", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("Cache-Control", containsString("private")))
                .andExpect(jsonPath("$.data.content[0].username").value("aedrum"))
                .andExpect(jsonPath("$.data.content[0].profilePicture").value("avatar.jpg"))
                .andExpect(jsonPath("$.data.content[0].status").value("PENDING"))
                .andExpect(jsonPath("$.data.content[0].role").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].email").doesNotExist())
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.number").value(0))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.last").value(true));
        verify(service).getPendingInvitations(bandId, actor, 0, 20);
    }

    @Test void forwardsExplicitPageAndSize() throws Exception {
        UUID actor = authenticate("ROLE_MUSICIAN", UserStatus.ACTIVE, true);
        when(service.getPendingInvitations(bandId, actor, 2, 10))
                .thenReturn(new PageResponse<>(List.of(), 2, 10, 0, 0, false, true));
        mvc.perform(get(path()).param("page", "2").param("size", "10")).andExpect(status().isOk());
        verify(service).getPendingInvitations(bandId, actor, 2, 10);
    }

    @Test void guestCannotReadPrivateInvitees() throws Exception {
        mvc.perform(get(path())).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @ParameterizedTest @ValueSource(strings = {"ROLE_VENUE", "ROLE_LISTENER", "ROLE_STUDIO"})
    void otherRolesCannotRead(String role) throws Exception {
        authenticate(role, UserStatus.ACTIVE, true);
        mvc.perform(get(path())).andExpect(status().isForbidden()); verifyNoInteractions(service);
    }

    @Test void disabledMusicianCannotRead() throws Exception {
        authenticate("ROLE_MUSICIAN", UserStatus.INACTIVE, true);
        mvc.perform(get(path())).andExpect(status().isForbidden()); verifyNoInteractions(service);
    }

    @Test void unverifiedMusicianCannotRead() throws Exception {
        authenticate("ROLE_MUSICIAN", UserStatus.ACTIVE, false);
        mvc.perform(get(path())).andExpect(status().isForbidden()); verifyNoInteractions(service);
    }

    @ParameterizedTest @ValueSource(strings = {"abc", "0.5", "2147483648"})
    void malformedPageRejectedBeforeService(String page) throws Exception {
        authenticate("ROLE_MUSICIAN", UserStatus.ACTIVE, true);
        mvc.perform(get(path()).param("page", page)).andExpect(status().isBadRequest()); verifyNoInteractions(service);
    }

    @Test void activeNonFounderUsesDocumentedPrivacyError() throws Exception {
        authenticate("ROLE_MUSICIAN", UserStatus.ACTIVE, true);
        when(service.getPendingInvitations(any(), any(), anyInt(), anyInt()))
                .thenThrow(new SoundConnectException(ErrorType.BAND_PENDING_INVITATIONS_FORBIDDEN));
        mvc.perform(get(path())).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(9217));
    }

    @Test void outOfBoundsPaginationUsesDocumentedBadRequestError() throws Exception {
        authenticate("ROLE_MUSICIAN", UserStatus.ACTIVE, true);
        when(service.getPendingInvitations(any(), any(), anyInt(), anyInt()))
                .thenThrow(new SoundConnectException(ErrorType.BAND_PENDING_INVITATIONS_PAGE_INVALID));
        mvc.perform(get(path()).param("size", "51")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(9218));
    }

    private UUID authenticate(String role, UserStatus status, boolean verified) {
        UUID id = UUID.randomUUID();
        var user = User.builder().id(id).username("owner").password("unused").email("owner@example.com")
                .emailVerified(verified).status(status).roles(Set.of(Role.builder().name(role).build())).build();
        var principal = UserDetailsImpl.fromUser(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        return id;
    }
}
