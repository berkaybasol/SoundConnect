package com.berkayb.soundconnect.modules.event.controller;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.event.controller.owner.EventVenueOwnerController;
import com.berkayb.soundconnect.modules.event.service.EventService;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
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
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = EventVenueOwnerController.class, excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE, classes = com.berkayb.soundconnect.auth.security.JwtAuthenticationFilter.class))
@AutoConfigureMockMvc(addFilters = false)
@Import(VenueOnlyEventApiRegressionTest.MethodSecurity.class)
class VenueOnlyEventApiRegressionTest {
    @TestConfiguration @EnableMethodSecurity static class MethodSecurity {}
    @Autowired MockMvc mvc;
    @Autowired RequestMappingHandlerMapping mappings;
    @MockitoBean EventService service;

    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @ParameterizedTest @ValueSource(strings = {"ROLE_MUSICIAN", "ROLE_VENUE"})
    void withdrawnReciprocalRoutesHaveNoControllerOrMutationForEitherRole(String role) throws Exception {
        authenticate(role);
        assertThat(mappings.getHandlerMethods().keySet().stream().flatMap(mapping -> mapping.getPatternValues().stream()))
                .noneMatch(path -> path.contains("musician-events") || path.contains("band-events") || path.contains("event-venue-requests"));
        // Class removal is checked as well: an accidentally unscanned controller must not silently revive later.
        for (String type : new String[]{"controller.user.MusicianEventController", "controller.user.BandEventController",
                "venue.controller.EventVenueRequestController", "service.MusicianEventService", "venue.service.EventVenueRequestService"}) {
            assertThat(getClass().getClassLoader().getResource("com/berkayb/soundconnect/modules/event/" + type.replace('.', '/') + ".class")).isNull();
        }
        verifyNoInteractions(service);
    }

    @Test void musicianCannotUseRemainingVenueCreationOrDeletionEndpoints() throws Exception {
        authenticate("ROLE_MUSICIAN");
        mvc.perform(post("/api/v1/venue-owner/events").contentType("application/json")
                .content("{\"title\":\"Gece\",\"eventDate\":\"2026-09-20\",\"startTime\":\"20:00\",\"venueId\":\"" + UUID.randomUUID() + "\",\"manualPerformerName\":\"Sanatçı\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/venue-owner/events/{eventId}", UUID.randomUUID())).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    private void authenticate(String role) {
        User user = User.builder().id(UUID.randomUUID()).username("venue_only_actor").password("unused").email("events@example.com")
                .emailVerified(true).status(UserStatus.ACTIVE).roles(Set.of(Role.builder().name(role).build())).build();
        UserDetailsImpl principal = UserDetailsImpl.fromUser(user);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
}
