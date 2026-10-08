package com.berkayb.soundconnect.modules.notification.controller.user;

import com.berkayb.soundconnect.auth.security.*;
import com.berkayb.soundconnect.auth.service.CustomUserDetailsService;
import com.berkayb.soundconnect.modules.application.venueapplication.service.VenueApplicationSessionAccess;
import com.berkayb.soundconnect.modules.notification.dto.response.NotificationResponseDto;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.notification.service.NotificationService;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.support.ListenerProfileChoiceStatusReader;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.GlobalExceptionHandler;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.security.SecurityErrorResponseWriter;
import com.berkayb.soundconnect.shared.util.JwtUtil;
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
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Uses the real authentication/profile gates; tokens and persistence are isolated test fixtures. */
@WebMvcTest(properties = "spring.config.import=")
@ContextConfiguration(classes = {NotificationController.class, GlobalExceptionHandler.class,
        NotificationLookupControllerTest.Security.class, JwtAuthenticationFilter.class,
        JwtUtil.class, ListenerProfileChoiceGate.class, SecurityErrorResponseWriter.class})
class NotificationLookupControllerTest {
    static final String BASE = "/api/v1/user/notifications";
    @Autowired MockMvc mvc;
    @MockitoBean NotificationService service;
    @MockitoBean JwtTokenProvider tokens;
    @MockitoBean CustomUserDetailsService users;
    @MockitoBean VenueApplicationSessionAccess applicationAccess;
    @MockitoBean ListenerProfileChoiceStatusReader choice;
    UUID owner, id;
    User account;

    @Configuration @EnableWebSecurity @EnableMethodSecurity
    static class Security {
        @Bean SecurityFilterChain chain(HttpSecurity http, JwtAuthenticationFilter filter) throws Exception {
            return http.csrf(c -> c.disable()).authorizeHttpRequests(a -> a.anyRequest().authenticated())
                    .addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class)
                    .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                    .build();
        }
    }

    @BeforeEach void setup() {
        owner = UUID.randomUUID();
        id = UUID.randomUUID();
        account = User.builder().id(owner).username("fixture").status(UserStatus.ACTIVE).emailVerified(true)
                .roles(Set.of(Role.builder().name("ROLE_LISTENER").build())).build();
        when(tokens.validateToken("fixture-token")).thenReturn(true);
        when(tokens.getUserIdFromToken("fixture-token")).thenReturn(owner);
        when(users.loadUserById(owner)).thenAnswer(invocation -> new UserDetailsImpl(account));
    }

    @Test void anonymousAndInvalidBearerCannotLookUpNotifications() throws Exception {
        mvc.perform(get(BASE + "/" + id)).andExpect(status().isUnauthorized());
        mvc.perform(get(BASE + "/" + id).header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test void authenticatedOwnerIsUsedAndLookupDoesNotAcknowledge() throws Exception {
        var dto = new NotificationResponseDto(id, owner, NotificationType.ARTIST_VENUE_LINK_APPLICATION_REQUEST,
                "fixture title", "fixture body", false, Instant.parse("2026-09-24T00:00:00Z"),
                Map.of("module", "ARTIST_VENUE", "requestId", UUID.randomUUID().toString()));
        when(service.getUserNotification(owner, id)).thenReturn(dto);
        mvc.perform(get(BASE + "/" + id).header("Authorization", "Bearer fixture-token")
                        .queryParam("recipientId", UUID.randomUUID().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(id.toString()))
                .andExpect(jsonPath("$.data.recipientId").value(owner.toString()))
                .andExpect(jsonPath("$.data.read").value(false));
        verify(service).getUserNotification(owner, id);
        verifyNoMoreInteractions(service);
    }

    @Test void missingAndOtherRecipientIdsHaveTheSameNotFoundResponse() throws Exception {
        for (UUID target : List.of(id, UUID.randomUUID())) {
            when(service.getUserNotification(owner, target)).thenThrow(new SoundConnectException(ErrorType.NOTIFICATION_NOT_FOUND));
            mvc.perform(get(BASE + "/" + target).header("Authorization", "Bearer fixture-token"))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(1900));
            verify(service).getUserNotification(owner, target);
        }
        verifyNoMoreInteractions(service);
    }

    @Test void malformedIdIsRejectedBeforeServiceLookup() throws Exception {
        mvc.perform(get(BASE + "/not-a-uuid").header("Authorization", "Bearer fixture-token"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void incompleteListenerChoiceUsesExistingGate() throws Exception {
        when(choice.requiresChoice(account)).thenReturn(true);
        mvc.perform(get(BASE + "/" + id).header("Authorization", "Bearer fixture-token"))
                .andExpect(status().isPreconditionRequired()).andExpect(jsonPath("$.code").value(1308));
        verifyNoInteractions(service);
    }

    @Test void inactiveUnverifiedAndErasedAccountsCannotLookUpNotifications() throws Exception {
        account.setEmailVerified(false); assertRejected();
        account.setEmailVerified(true); account.setStatus(UserStatus.PENDING_STUDIO_REQUEST); assertRejected();
        account.setStatus(UserStatus.ACTIVE); account.setErasedAt(LocalDateTime.now()); assertRejected();
        verifyNoInteractions(service);
    }

    @Test void staticRecentAndUnreadRoutesRemainDistinctFromLookup() throws Exception {
        when(service.getRecentNotifications(owner)).thenReturn(List.of());
        when(service.getUnreadCount(owner)).thenReturn(7L);
        mvc.perform(get(BASE + "/recent").header("Authorization", "Bearer fixture-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
        mvc.perform(get(BASE + "/unread-count").header("Authorization", "Bearer fixture-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.unread").value(7));
        verify(service).getRecentNotifications(owner);
        verify(service).getUnreadCount(owner);
        verifyNoMoreInteractions(service);
    }

    private void assertRejected() throws Exception {
        mvc.perform(get(BASE + "/" + id).header("Authorization", "Bearer fixture-token"))
                .andExpect(status().isUnauthorized());
    }
}
