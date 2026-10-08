package com.berkayb.soundconnect.modules.notification.push;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(properties={"spring.config.import=","app.notification.push.enabled=true"})
@ContextConfiguration(classes={PushDeviceController.class,PushAdminController.class,PushApiSecurityTest.Security.class,
        com.berkayb.soundconnect.shared.exception.GlobalExceptionHandler.class})
class PushApiSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean PushDeviceService devices;
    @MockitoBean PushDeviceRateLimit rateLimit;
    @MockitoBean PushOperations operations;
    @BeforeEach void resetStartupInvocation() { clearInvocations(devices,operations); }
    @Configuration @EnableWebSecurity @EnableMethodSecurity
    static class Security {
        @Bean SecurityFilterChain chain(HttpSecurity http) throws Exception {
            return http.csrf(csrf->csrf.disable()).authorizeHttpRequests(auth->auth.anyRequest().authenticated())
                    .exceptionHandling(ex->ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))).build();
        }
    }
    @Test void anonymousCannotRegisterReadPreferencesOrReadOperations() throws Exception {
        mvc.perform(get("/api/v1/user/notifications/push/preferences")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/notifications/push/summary")).andExpect(status().isUnauthorized());
        verifyNoInteractions(devices,operations);
    }
    @Test void regularAndListenerAccountsCannotOperateQueueEvenWithMixedAdminRole() throws Exception {
        mvc.perform(get("/api/v1/admin/notifications/push/summary").with(user("regular").roles("USER"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/notifications/push/summary").with(user("listener").roles("ADMIN","LISTENER"))).andExpect(status().isForbidden());
        verifyNoInteractions(operations);
    }
    @Test void authorizedOperatorCanInspectBoundedNonSensitiveSummary() throws Exception {
        when(operations.summary()).thenReturn(new PushOperations.Summary(Map.of("PENDING",2L),null,false));
        mvc.perform(get("/api/v1/admin/notifications/push/summary").with(user("owner").roles("OWNER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.counts.PENDING").value(2));
        mvc.perform(get("/api/v1/admin/notifications/push/failed?limit=101").with(user("owner").roles("OWNER")))
                .andExpect(status().isBadRequest());
    }
    @Test void registrationUsesAuthenticatedOwnerAndIgnoresSpoofedBodyUserId() throws Exception {
        UUID owner=UUID.randomUUID(),installation=UUID.randomUUID();
        UserDetailsImpl principal=mock(UserDetailsImpl.class); when(principal.getId()).thenReturn(owner);
        var auth=new UsernamePasswordAuthenticationToken(principal,"unused",List.of(new SimpleGrantedAuthority("ROLE_LISTENER")));
        mvc.perform(put("/api/v1/user/notifications/push/devices/"+installation).with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"token":"opaque-token","platform":"ANDROID","permission":"AUTHORIZED","clientRevision":1,"userId":"%s"}
                        """.formatted(UUID.randomUUID())))
                .andExpect(status().isOk());
        verify(devices).register(eq(owner),eq(installation),any());
        mvc.perform(delete("/api/v1/user/notifications/push/devices/"+installation+"?clientRevision=2").with(authentication(auth)))
                .andExpect(status().isOk());
        verify(devices).revoke(owner,installation,2L);
    }
    @Test void malformedRegistrationIsRejectedBeforeItReachesStorage() throws Exception {
        mvc.perform(put("/api/v1/user/notifications/push/devices/"+UUID.randomUUID()).with(user("user"))
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"token":"","platform":"ANDROID","permission":"AUTHORIZED"}
                        """))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(devices);
    }
    @Test void missingInvalidAndOverflowRevisionFailClosedForBothMutationRoutes() throws Exception {
        for(String revision:List.of("null","0","-1","9007199254740992","9223372036854775808")) {
            mvc.perform(put("/api/v1/user/notifications/push/devices/"+UUID.randomUUID()).with(user("user"))
                    .contentType(MediaType.APPLICATION_JSON).content("""
                            {"token":"fixture-token","platform":"ANDROID","permission":"AUTHORIZED","clientRevision":%s}
                            """.formatted(revision))).andExpect(status().isBadRequest());
            String query=revision.equals("null") ? "" : "?clientRevision="+revision;
            mvc.perform(delete("/api/v1/user/notifications/push/devices/"+UUID.randomUUID()+query).with(user("user")))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(devices,rateLimit);
    }
    @Test void accountRateLimitReturnsRetryAfterBeforeAnyDatabaseMutation() throws Exception {
        UUID owner=UUID.randomUUID(); var principal=mock(UserDetailsImpl.class); when(principal.getId()).thenReturn(owner);
        var auth=new UsernamePasswordAuthenticationToken(principal,"unused",List.of(new SimpleGrantedAuthority("ROLE_USER")));
        doThrow(new com.berkayb.soundconnect.shared.exception.RateLimitedException(
                com.berkayb.soundconnect.shared.exception.ErrorType.PUSH_DEVICE_RATE_LIMITED,17)).when(rateLimit).check(owner);
        mvc.perform(put("/api/v1/user/notifications/push/devices/"+UUID.randomUUID()).with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"token":"fixture-token","platform":"ANDROID","permission":"AUTHORIZED","clientRevision":1}
                        """)).andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After","17"));
        verifyNoInteractions(devices);
    }
}
