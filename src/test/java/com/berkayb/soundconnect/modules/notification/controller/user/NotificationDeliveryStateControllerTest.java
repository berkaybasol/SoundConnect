package com.berkayb.soundconnect.modules.notification.controller.user;

import com.berkayb.soundconnect.auth.security.*;
import com.berkayb.soundconnect.auth.service.CustomUserDetailsService;
import com.berkayb.soundconnect.modules.application.venueapplication.service.VenueApplicationSessionAccess;
import com.berkayb.soundconnect.modules.notification.service.NotificationService;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.support.ListenerProfileChoiceStatusReader;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.shared.exception.GlobalExceptionHandler;
import com.berkayb.soundconnect.shared.security.SecurityErrorResponseWriter;
import com.berkayb.soundconnect.shared.util.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.time.LocalDateTime;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real authentication/profile filters and method security; only token validation and storage are stubbed. */
@WebMvcTest(properties="spring.config.import=")
@ContextConfiguration(classes={NotificationController.class,GlobalExceptionHandler.class,
        NotificationDeliveryStateControllerTest.Security.class,JwtAuthenticationFilter.class,
        JwtUtil.class,ListenerProfileChoiceGate.class,SecurityErrorResponseWriter.class})
class NotificationDeliveryStateControllerTest {
    static final String URL="/api/v1/user/notifications/delivery-state";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean NotificationService service;
    @MockitoBean JwtTokenProvider tokens;
    @MockitoBean CustomUserDetailsService users;
    @MockitoBean VenueApplicationSessionAccess applicationAccess;
    @MockitoBean ListenerProfileChoiceStatusReader choice;
    UUID owner;
    User account;

    @Configuration @EnableWebSecurity @EnableMethodSecurity
    static class Security {
        @Bean SecurityFilterChain chain(HttpSecurity http,JwtAuthenticationFilter filter) throws Exception {
            return http.csrf(c->c.disable()).authorizeHttpRequests(a->a.anyRequest().authenticated())
                    .addFilterBefore(filter,UsernamePasswordAuthenticationFilter.class)
                    .exceptionHandling(e->e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))).build();
        }
    }
    @BeforeEach void setup() {
        owner=UUID.randomUUID();
        account=User.builder().id(owner).username("fixture").status(UserStatus.ACTIVE).emailVerified(true)
                .roles(Set.of(Role.builder().name("ROLE_LISTENER").build())).build();
        when(tokens.validateToken("fixture-token")).thenReturn(true);
        when(tokens.getUserIdFromToken("fixture-token")).thenReturn(owner);
        when(users.loadUserById(owner)).thenAnswer(invocation->new UserDetailsImpl(account));
    }
    @Test void anonymousAndInvalidBearerCannotQueryDeliveryState() throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{\"notificationIds\":[]}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(URL).header("Authorization","Bearer invalid").contentType(MediaType.APPLICATION_JSON)
                .content("{\"notificationIds\":[]}")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
    @Test void onlyAuthenticatedRecipientIsUsedAndResponseContainsNoExistenceMetadata() throws Exception {
        UUID id=UUID.randomUUID(),spoof=UUID.randomUUID();
        when(service.getDismissedDeliveryIds(owner,List.of(id))).thenReturn(List.of(id));
        mvc.perform(post(URL).header("Authorization","Bearer fixture-token").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("notificationIds",List.of(id),"recipientId",spoof))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.dismissedIds[0]").value(id.toString()))
                .andExpect(jsonPath("$.data.length()").value(1));
        verify(service).getDismissedDeliveryIds(owner,List.of(id)); verifyNoMoreInteractions(service);
    }
    @Test void emptyAndOneHundredIdsAreAccepted() throws Exception {
        for(int count:new int[]{0,100}) {
            var ids=java.util.stream.IntStream.range(0,count).mapToObj(i->UUID.randomUUID()).toList();
            when(service.getDismissedDeliveryIds(owner,ids)).thenReturn(List.of());
            mvc.perform(post(URL).header("Authorization","Bearer fixture-token").contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("notificationIds",ids))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.dismissedIds").isEmpty());
            verify(service).getDismissedDeliveryIds(owner,ids);
        }
        verifyNoMoreInteractions(service);
    }
    @Test void nullMissingMalformedElementAndOversizedRequestsNeverReachService() throws Exception {
        var bodies=new ArrayList<>(List.of("{}","null","{\"notificationIds\":null}","{\"notificationIds\":[null]}",
                "{\"notificationIds\":[\"not-a-uuid\"]}","{\"notificationIds\":\"wrong-type\"}"));
        bodies.add(json.writeValueAsString(Map.of("notificationIds",Collections.nCopies(101,owner))));
        for(String body:bodies) mvc.perform(post(URL).header("Authorization","Bearer fixture-token")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void incompleteListenerChoiceUsesExisting428Gate() throws Exception {
        when(choice.requiresChoice(account)).thenReturn(true);
        mvc.perform(post(URL).header("Authorization","Bearer fixture-token").contentType(MediaType.APPLICATION_JSON)
                .content("{\"notificationIds\":[]}")).andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value(1308));
        verifyNoInteractions(service);
    }
    @Test void inactiveUnverifiedAndErasedAccountsCannotQuery() throws Exception {
        account.setEmailVerified(false); assertRejected();
        account.setEmailVerified(true); account.setStatus(UserStatus.PENDING_STUDIO_REQUEST); assertRejected();
        account.setStatus(UserStatus.ACTIVE); account.setErasedAt(LocalDateTime.now()); assertRejected();
        verifyNoInteractions(service);
    }
    private void assertRejected() throws Exception {
        mvc.perform(post(URL).header("Authorization","Bearer fixture-token").contentType(MediaType.APPLICATION_JSON)
                .content("{\"notificationIds\":[]}")).andExpect(status().isUnauthorized());
    }
}
