package com.berkayb.soundconnect.modules.studio.security;

import com.berkayb.soundconnect.auth.security.JwtAuthenticationFilter;
import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.studio.reservation.controller.StudioReservationUserController;
import com.berkayb.soundconnect.modules.studio.reservation.service.StudioReservationService;
import com.berkayb.soundconnect.modules.studio.room.dto.response.StudioPageResponse;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = StudioReservationUserController.class, excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE, classes = JwtAuthenticationFilter.class))
@AutoConfigureMockMvc(addFilters = false)
@Import(StudioReservationCustomerAudienceControllerTest.MethodSecurity.class)
class StudioReservationCustomerAudienceControllerTest {
    @TestConfiguration @EnableMethodSecurity static class MethodSecurity {}
    @Autowired MockMvc mvc;
    @MockitoBean StudioReservationService service;
    private final UUID resource = UUID.randomUUID();
    private static final String BASE = "/api/v1/user/studio-reservations";

    @AfterEach void clearViewer() { SecurityContextHolder.clearContext(); }

    @ParameterizedTest @EnumSource(Route.class)
    void listenerCannotReachAnyCustomerReadOrWrite(Route route) throws Exception {
        authenticate("ROLE_LISTENER");
        mvc.perform(request(route)).andExpect(status().isForbidden()).andExpect(jsonPath("$.data").doesNotExist());
        verifyNoInteractions(service);
    }

    @ParameterizedTest @EnumSource(Route.class)
    void anonymousCannotReachAnyCustomerReadOrWrite(Route route) throws Exception {
        mvc.perform(request(route)).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @ParameterizedTest @EnumSource(Route.class)
    void musicianCustomerKeepsTheExistingRouteAndAuthenticatedIdentity(Route route) throws Exception {
        UUID actor = authenticate("ROLE_MUSICIAN");
        switch (route) {
            case LIST -> when(service.listCustomer(actor, 0, 20))
                    .thenReturn(new StudioPageResponse<>(List.of(), 0, 20, 0, 0, true, true));
            case ROOM_DATE -> when(service.listCustomerRoomDate(actor, resource, LocalDate.of(2026, 9, 14), 0, 20))
                    .thenReturn(new StudioPageResponse<>(List.of(), 0, 20, 0, 0, true, true));
            default -> { }
        }
        mvc.perform(request(route)).andExpect(status().is(route == Route.CREATE ? 201 : 200));
        switch (route) {
            case CREATE -> verify(service).create(eq(actor), any());
            case LIST -> verify(service).listCustomer(actor, 0, 20);
            case ROOM_DATE -> verify(service).listCustomerRoomDate(actor, resource, LocalDate.of(2026, 9, 14), 0, 20);
            case CANCEL -> verify(service).cancelCustomer(eq(actor), eq(resource), any());
        }
    }

    private MockHttpServletRequestBuilder request(Route route) {
        return switch (route) {
            case CREATE -> post(BASE).contentType("application/json").content("""
                    {"roomId":"%s","date":"2026-09-14","startTime":"13:00:00",
                     "durationHours":2,"contactPhone":"05551112233","clientRequestId":"%s"}
                    """.formatted(resource, UUID.randomUUID()));
            case LIST -> get(BASE);
            case ROOM_DATE -> get(BASE + "/rooms/{id}", resource).param("date", "2026-09-14");
            case CANCEL -> post(BASE + "/{id}/cancel", resource).contentType("application/json")
                    .content("{\"expectedVersion\":0}");
        };
    }

    private UUID authenticate(String role) {
        UUID id = UUID.randomUUID();
        var user = User.builder().id(id).username("customer").password("unused").email("customer@example.test")
                .emailVerified(true).status(UserStatus.ACTIVE).roles(Set.of(Role.builder().name(role).build())).build();
        var principal = UserDetailsImpl.fromUser(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        return id;
    }

    enum Route { CREATE, LIST, ROOM_DATE, CANCEL }
}
