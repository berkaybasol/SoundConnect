package com.berkayb.soundconnect.modules.studio.security;

import com.berkayb.soundconnect.auth.security.JwtAuthenticationFilter;
import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.application.studioapplication.controller.admin.StudioApplicationAdminController;
import com.berkayb.soundconnect.modules.application.studioapplication.controller.user.UserStudioApplicationController;
import com.berkayb.soundconnect.modules.application.studioapplication.service.StudioApplicationService;
import com.berkayb.soundconnect.modules.backline.catalog.controller.BacklineCatalogAdminController;
import com.berkayb.soundconnect.modules.backline.catalog.controller.BacklineCategoryRequestOwnerController;
import com.berkayb.soundconnect.modules.backline.catalog.service.BacklineCatalogService;
import com.berkayb.soundconnect.modules.profile.StudioProfile.controller.admin.StudioProfileAdminController;
import com.berkayb.soundconnect.modules.profile.StudioProfile.controller.user.StudioProfileUserController;
import com.berkayb.soundconnect.modules.profile.StudioProfile.service.StudioProfileService;
import com.berkayb.soundconnect.modules.studio.equipment.controller.StudioEquipmentOwnerController;
import com.berkayb.soundconnect.modules.studio.equipment.service.StudioEquipmentService;
import com.berkayb.soundconnect.modules.studio.reservation.service.StudioReservationService;
import com.berkayb.soundconnect.modules.studio.room.controller.StudioRoomOwnerController;
import com.berkayb.soundconnect.modules.studio.room.service.StudioRoomService;
import com.berkayb.soundconnect.modules.track.controller.StudioTrackController;
import com.berkayb.soundconnect.modules.track.enums.TrackOwnerType;
import com.berkayb.soundconnect.modules.track.service.TrackService;
import com.berkayb.soundconnect.modules.user.entity.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Corrupt/legacy mixed authorities are fixtures, not supported account provisioning. */
@WebMvcTest(controllers = {StudioProfileUserController.class, StudioProfileAdminController.class,
        StudioRoomOwnerController.class, StudioEquipmentOwnerController.class, StudioTrackController.class,
        BacklineCategoryRequestOwnerController.class, BacklineCatalogAdminController.class,
        StudioApplicationAdminController.class, UserStudioApplicationController.class},
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = JwtAuthenticationFilter.class))
@AutoConfigureMockMvc(addFilters = false)
@Import(StudioMixedAudienceControllerTest.MethodSecurity.class)
class StudioMixedAudienceControllerTest {
    @TestConfiguration @EnableMethodSecurity static class MethodSecurity {}
    @Autowired MockMvc mvc;
    @MockitoBean StudioProfileService profiles;
    @MockitoBean StudioRoomService rooms;
    @MockitoBean StudioEquipmentService equipment;
    @MockitoBean StudioReservationService reservations;
    @MockitoBean TrackService tracks;
    @MockitoBean BacklineCatalogService catalog;
    @MockitoBean StudioApplicationService applications;
    final UUID actor = UUID.randomUUID(), resource = UUID.randomUUID();

    @AfterEach void clearViewer() { SecurityContextHolder.clearContext(); }

    @ParameterizedTest @EnumSource(Route.class)
    void listenerVetoWinsOverLegacyStudioAndAdministrativeAuthorities(Route route) throws Exception {
        authenticate(true);
        mvc.perform(request(route)).andExpect(status().isForbidden()).andExpect(jsonPath("$.data").doesNotExist());
        verifyNoInteractions(profiles, rooms, equipment, reservations, tracks, catalog, applications);
    }

    @ParameterizedTest @EnumSource(Route.class)
    void authorizedStudioAndAdministrativeRoutesKeepTheirExistingBehavior(Route route) throws Exception {
        authenticate(false);
        if (route == Route.CATEGORY_OWNER) when(catalog.listOwnerRequests(actor, 0, 20)).thenReturn(Page.empty());
        if (route == Route.CATEGORY_ADMIN) when(catalog.listAdminRequests(null, 0, 20)).thenReturn(Page.empty());
        mvc.perform(request(route)).andExpect(status().isOk());
        switch (route) {
            case PROFILE_OWNER -> verify(profiles).getProfileByUserId(actor);
            case PROFILE_ADMIN -> verify(profiles).getProfileByUserId(resource);
            case ROOM_OWNER -> verify(rooms).getOwner(actor, resource);
            case EQUIPMENT_OWNER -> verify(equipment).getOwner(actor, resource);
            case CATEGORY_OWNER -> verify(catalog).listOwnerRequests(actor, 0, 20);
            case CATEGORY_ADMIN -> verify(catalog).listAdminRequests(null, 0, 20);
            case APPLICATION_OWNER -> verify(applications).getPendingApplicationByUser(actor);
            case APPLICATION_ADMIN -> verify(applications).getById(resource);
            case TRACK_CREATE -> verify(tracks).createTrack(eq(resource), eq(actor), any());
            case TRACK_DELETE -> verify(tracks).deleteTrack(resource, resource, actor, TrackOwnerType.STUDIO_PROFILE);
        }
    }

    private MockHttpServletRequestBuilder request(Route route) {
        return switch (route) {
            case PROFILE_OWNER -> get("/api/v1/user/studio-profiles/me");
            case PROFILE_ADMIN -> get("/api/v1/admin/studio-profiles/by-user/{id}", resource);
            case ROOM_OWNER -> get("/api/v1/user/studio-profiles/me/rooms/{id}", resource);
            case EQUIPMENT_OWNER -> get("/api/v1/user/studio-profiles/me/equipment/{id}", resource);
            case CATEGORY_OWNER -> get("/api/v1/user/studio-profiles/me/category-requests");
            case CATEGORY_ADMIN -> get("/api/v1/admin/backline/category-requests");
            case APPLICATION_OWNER -> get("/api/v1/user/studio-applications/my/pending");
            case APPLICATION_ADMIN -> get("/api/v1/admin/studio-applications/{id}", resource);
            case TRACK_CREATE -> post("/api/v1/studio-profiles/{id}/tracks", resource).contentType("application/json")
                    .content("{\"mediaAssetId\":\"" + resource + "\",\"title\":\"Studio recording\"}");
            case TRACK_DELETE -> delete("/api/v1/studio-profiles/{id}/tracks/{trackId}", resource, resource);
        };
    }

    private void authenticate(boolean listener) {
        var authorityNames = new ArrayList<>(List.of("ROLE_STUDIO", "MANAGE_PROFILES", "MANAGE_STUDIO_APPLICATIONS", "MANAGE_BACKLINE_CATALOG"));
        if (listener) authorityNames.add("ROLE_LISTENER");
        var principal = UserDetailsImpl.fromUser(User.builder().id(actor).username("studio").build());
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                principal, "n/a", authorityNames.stream().map(SimpleGrantedAuthority::new).toList()));
    }

    enum Route { PROFILE_OWNER, PROFILE_ADMIN, ROOM_OWNER, EQUIPMENT_OWNER, CATEGORY_OWNER, CATEGORY_ADMIN,
        APPLICATION_OWNER, APPLICATION_ADMIN, TRACK_CREATE, TRACK_DELETE }
}
