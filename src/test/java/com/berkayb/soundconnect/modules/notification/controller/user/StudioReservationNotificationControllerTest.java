package com.berkayb.soundconnect.modules.notification.controller.user;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.notification.dto.response.StudioReservationNotificationTargetResponse;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.notification.service.StudioReservationNotificationTargetService;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.studio.reservation.enums.StudioReservationStatus;
import com.berkayb.soundconnect.modules.user.entity.User;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import java.time.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringJUnitConfig(StudioReservationNotificationControllerTest.Config.class)
@WebAppConfiguration
class StudioReservationNotificationControllerTest {
    @Configuration @EnableWebMvc @EnableWebSecurity @EnableMethodSecurity
    static class Config {
        @Bean StudioReservationNotificationTargetService targets() { return mock(StudioReservationNotificationTargetService.class); }
        @Bean StudioReservationNotificationController controller(StudioReservationNotificationTargetService targets) {
            return new StudioReservationNotificationController(targets);
        }
        @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
            return http.authorizeHttpRequests(a->a.anyRequest().authenticated())
                    .exceptionHandling(e->e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))).build();
        }
    }
    @Autowired WebApplicationContext context;
    @Autowired StudioReservationNotificationTargetService targets;
    MockMvc mvc;
    final UUID recipient=UUID.randomUUID(),notification=UUID.randomUUID(),reservation=UUID.randomUUID();
    final String base="/api/v1/user/notifications/";
    @BeforeEach void setup() { reset(targets); mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build(); }

    @Test void onlySignedPrincipalSelectsReaderAndJsonMatchesExactContract() throws Exception {
        var start=Instant.parse("2026-09-25T10:00:00Z");
        when(targets.resolve(recipient,notification)).thenReturn(new StudioReservationNotificationTargetResponse(
                notification,recipient,NotificationType.STUDIO_RESERVATION_APPROVED,reservation,UUID.randomUUID(),UUID.randomUUID(),
                "Stüdyo","Oda",false,StudioReservationStatus.CANCELLED_BY_STUDIO,true,false,
                start,start.plusSeconds(3600),"Europe/Istanbul",LocalDate.of(2026,9,25),LocalDate.of(2026,9,25),LocalTime.of(13,0),LocalTime.of(14,0)));
        mvc.perform(get(base+notification+"/studio-reservation").with(user(principal("ROLE_MUSICIAN")))
                        .param("recipientId",UUID.randomUUID().toString()).param("ownerMode","true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.notificationId").value(notification.toString()))
                .andExpect(jsonPath("$.data.recipientId").value(recipient.toString()))
                .andExpect(jsonPath("$.data.reservationId").value(reservation.toString()))
                .andExpect(jsonPath("$.data.ownerMode").value(false))
                .andExpect(jsonPath("$.data.status").value("CANCELLED_BY_STUDIO"))
                .andExpect(jsonPath("$.data.roomArchived").value(true))
                .andExpect(jsonPath("$.data.startsAt").value("2026-09-25T10:00:00Z"))
                .andExpect(jsonPath("$.data.endsAt").value("2026-09-25T11:00:00Z"))
                .andExpect(jsonPath("$.data.localDate").value("2026-09-25"))
                .andExpect(jsonPath("$.data.localEndDate").value("2026-09-25"))
                .andExpect(jsonPath("$.data.localStartTime").value("13:00:00"))
                .andExpect(jsonPath("$.data.localEndTime").value("14:00:00"))
                .andExpect(jsonPath("$.data.requesterId").doesNotExist())
                .andExpect(jsonPath("$.data.phone").doesNotExist());
        verify(targets).resolve(recipient,notification); verifyNoMoreInteractions(targets);
    }
    @Test void unauthenticatedCannotResolve() throws Exception {
        mvc.perform(get(base+notification+"/studio-reservation")).andExpect(status().isUnauthorized());
        verifyNoInteractions(targets);
    }
    @Test void listenerAuthorityCannotResolveEvenWithAnotherRole() throws Exception {
        mvc.perform(get(base+notification+"/studio-reservation").with(user(principal("ROLE_LISTENER","ROLE_STUDIO"))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(targets);
    }
    @Test void malformedNotificationIdDoesNotReachTarget() throws Exception {
        mvc.perform(get(base+"not-a-uuid/studio-reservation").with(user(principal("ROLE_MUSICIAN"))))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(targets);
    }
    private UserDetailsImpl principal(String... roles) {
        var entity=new User(); entity.setId(recipient); entity.setUsername("reader"); entity.setPassword("unused");
        var values=new HashSet<Role>();
        for(var name:roles) { var role=new Role(); role.setName(name); role.setPermissions(new HashSet<>()); values.add(role); }
        entity.setRoles(values); entity.setPermissions(new HashSet<>());
        return UserDetailsImpl.fromUser(entity);
    }
}
