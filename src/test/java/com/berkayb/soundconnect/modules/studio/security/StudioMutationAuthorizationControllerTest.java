package com.berkayb.soundconnect.modules.studio.security;

import com.berkayb.soundconnect.auth.security.JwtAuthenticationFilter;
import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.studio.equipment.controller.StudioEquipmentOwnerController;
import com.berkayb.soundconnect.modules.studio.equipment.dto.EquipmentUpdateRequest;
import com.berkayb.soundconnect.modules.studio.equipment.service.StudioEquipmentService;
import com.berkayb.soundconnect.modules.studio.reservation.controller.StudioReservationUserController;
import com.berkayb.soundconnect.modules.studio.reservation.dto.request.StudioVersionRequest;
import com.berkayb.soundconnect.modules.studio.reservation.service.StudioReservationService;
import com.berkayb.soundconnect.modules.studio.room.controller.StudioRoomOwnerController;
import com.berkayb.soundconnect.modules.studio.room.controller.StudioRoomPublicController;
import com.berkayb.soundconnect.modules.studio.room.dto.request.StudioRoomUpdateRequest;
import com.berkayb.soundconnect.modules.studio.room.service.StudioRoomService;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {
        StudioRoomOwnerController.class,
        StudioRoomPublicController.class,
        StudioEquipmentOwnerController.class,
        StudioReservationUserController.class
})
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@Import(StudioMutationAuthorizationControllerTest.TestMvcConfig.class)
@Tag("web")
class StudioMutationAuthorizationControllerTest {

    @MockitoBean StudioRoomService roomService;
    @MockitoBean StudioEquipmentService equipmentService;
    @MockitoBean StudioReservationService reservationService;
    // This controller slice deliberately disables servlet filters. Mock the
    // filter boundary itself; its authorization collaborators are covered by
    // dedicated security tests.
    @MockitoBean JwtAuthenticationFilter jwtAuthenticationFilter;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserDetailsImpl testPrincipal;

    private UUID actorId;

    @BeforeEach
    void setUp() {
        actorId = UUID.randomUUID();
        when(testPrincipal.getId()).thenReturn(actorId);
    }

    @Test
    void foreignRoomUpdateUsesTheUniformNotFoundHttpContract() throws Exception {
        UUID foreignRoomId = UUID.randomUUID();
        StudioRoomUpdateRequest request = new StudioRoomUpdateRequest(
                0L, "A Odasi", null, 4, 4, null, "TRY", false,
                List.of(), List.of()
        );
        when(roomService.update(eq(actorId), eq(foreignRoomId), any()))
                .thenThrow(new SoundConnectException(ErrorType.STUDIO_ROOM_NOT_FOUND));

        mockMvc.perform(put("/api/v1/user/studio-profiles/me/rooms/{roomId}", foreignRoomId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorType.STUDIO_ROOM_NOT_FOUND.getCode()))
                .andExpect(jsonPath("$.httpStatus").value("NOT_FOUND"));

        verify(roomService).update(eq(actorId), eq(foreignRoomId), any());
    }

    @Test
    void foreignRoomReadUsesTheUniformNotFoundHttpContract() throws Exception {
        UUID foreignRoomId = UUID.randomUUID();
        when(roomService.getOwner(actorId, foreignRoomId))
                .thenThrow(new SoundConnectException(ErrorType.STUDIO_ROOM_NOT_FOUND));

        mockMvc.perform(get(
                        "/api/v1/user/studio-profiles/me/rooms/{roomId}",
                        foreignRoomId
                ))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorType.STUDIO_ROOM_NOT_FOUND.getCode()))
                .andExpect(jsonPath("$.httpStatus").value("NOT_FOUND"));

        verify(roomService).getOwner(actorId, foreignRoomId);
    }

    @Test
    void ownerRoomListRejectsSizeAboveServiceLimit() throws Exception {
        mockMvc.perform(get("/api/v1/user/studio-profiles/me/rooms")
                        .param("size", "51"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorType.CONSTRAINT_VIOLATION.getCode()))
                .andExpect(jsonPath("$.httpStatus").value("BAD_REQUEST"));

        verify(roomService, never()).listOwner(any(), anyInt(), anyInt());
    }

    @Test
    void publicRoomListRejectsSizeAboveServiceLimit() throws Exception {
        UUID profileId = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/public/studio-profiles/{profileId}/rooms", profileId)
                        .param("size", "51"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorType.CONSTRAINT_VIOLATION.getCode()))
                .andExpect(jsonPath("$.httpStatus").value("BAD_REQUEST"));

        verify(roomService, never()).listPublic(eq(profileId), anyInt(), anyInt());
    }

    @Test
    void foreignEquipmentUpdateUsesTheUniformNotFoundHttpContract() throws Exception {
        UUID foreignEquipmentId = UUID.randomUUID();
        EquipmentUpdateRequest request = new EquipmentUpdateRequest(
                0L, UUID.randomUUID(), "Shure SM58", null, null, null,
                1, List.of(), List.of()
        );
        when(equipmentService.update(eq(actorId), eq(foreignEquipmentId), any()))
                .thenThrow(new SoundConnectException(ErrorType.STUDIO_EQUIPMENT_NOT_FOUND));

        mockMvc.perform(put(
                        "/api/v1/user/studio-profiles/me/equipment/{equipmentId}",
                        foreignEquipmentId
                )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorType.STUDIO_EQUIPMENT_NOT_FOUND.getCode()))
                .andExpect(jsonPath("$.httpStatus").value("NOT_FOUND"));

        verify(equipmentService).update(eq(actorId), eq(foreignEquipmentId), any());
    }

    @Test
    void foreignEquipmentReadUsesTheUniformNotFoundHttpContract() throws Exception {
        UUID foreignEquipmentId = UUID.randomUUID();
        when(equipmentService.getOwner(actorId, foreignEquipmentId))
                .thenThrow(new SoundConnectException(ErrorType.STUDIO_EQUIPMENT_NOT_FOUND));

        mockMvc.perform(get(
                        "/api/v1/user/studio-profiles/me/equipment/{equipmentId}",
                        foreignEquipmentId
                ))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorType.STUDIO_EQUIPMENT_NOT_FOUND.getCode()))
                .andExpect(jsonPath("$.httpStatus").value("NOT_FOUND"));

        verify(equipmentService).getOwner(actorId, foreignEquipmentId);
    }

    @Test
    void foreignReservationCancellationUsesTheUniformNotFoundHttpContract() throws Exception {
        UUID foreignReservationId = UUID.randomUUID();
        StudioVersionRequest request = new StudioVersionRequest(0L);
        when(reservationService.cancelCustomer(eq(actorId), eq(foreignReservationId), any()))
                .thenThrow(new SoundConnectException(ErrorType.STUDIO_RESERVATION_NOT_FOUND));

        mockMvc.perform(post(
                        "/api/v1/user/studio-reservations/{reservationId}/cancel",
                        foreignReservationId
                )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorType.STUDIO_RESERVATION_NOT_FOUND.getCode()))
                .andExpect(jsonPath("$.httpStatus").value("NOT_FOUND"));

        verify(reservationService).cancelCustomer(
                eq(actorId), eq(foreignReservationId), any()
        );
    }

    @TestConfiguration
    static class TestMvcConfig implements WebMvcConfigurer {

        @Bean
        UserDetailsImpl testPrincipal() {
            return org.mockito.Mockito.mock(
                    UserDetailsImpl.class, Answers.RETURNS_DEEP_STUBS
            );
        }

        @Bean
        HandlerMethodArgumentResolver userDetailsImplResolver(UserDetailsImpl principal) {
            return new HandlerMethodArgumentResolver() {
                @Override
                public boolean supportsParameter(MethodParameter parameter) {
                    return UserDetailsImpl.class.isAssignableFrom(parameter.getParameterType());
                }

                @Override
                public Object resolveArgument(
                        MethodParameter parameter,
                        ModelAndViewContainer mavContainer,
                        NativeWebRequest webRequest,
                        WebDataBinderFactory binderFactory
                ) {
                    return principal;
                }
            };
        }

        @Override
        public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
            resolvers.add(userDetailsImplResolver(testPrincipal()));
        }
    }
}
