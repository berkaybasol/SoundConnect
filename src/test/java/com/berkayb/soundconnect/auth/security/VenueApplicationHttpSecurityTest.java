package com.berkayb.soundconnect.auth.security;

import com.berkayb.soundconnect.auth.ratelimit.AuthRateLimitFilter;
import com.berkayb.soundconnect.auth.service.CustomUserDetailsService;
import com.berkayb.soundconnect.modules.application.venueapplication.controller.VenueApplicationPushController;
import com.berkayb.soundconnect.modules.application.venueapplication.controller.VenueApplicationSessionController;
import com.berkayb.soundconnect.modules.application.venueapplication.service.VenueApplicationSessionAccess;
import com.berkayb.soundconnect.modules.application.venueapplication.service.VenueApplicationSessionService;
import com.berkayb.soundconnect.modules.notification.push.PushDeviceRateLimit;
import com.berkayb.soundconnect.modules.notification.push.PushDeviceService;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.shared.config.SecurityConfig;
import com.berkayb.soundconnect.shared.security.RestAccessDeniedHandler;
import com.berkayb.soundconnect.shared.security.RestAuthenticationEntryPoint;
import com.berkayb.soundconnect.shared.security.SecurityErrorResponseWriter;
import com.berkayb.soundconnect.shared.util.JwtUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real signed JWT + production security chain + actual mappings; no database/provider. */
@WebMvcTest(controllers = {VenueApplicationSessionController.class, VenueApplicationPushController.class}, properties = {
        "app.notification.push.enabled=true", "app.jwt.secret=0123456789abcdef0123456789abcdef",
        "app.jwt.expiration=60000", "app.jwt.issuer=venue-application-http-test"
})
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtTokenProvider.class, JwtUtil.class,
        RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class, SecurityErrorResponseWriter.class})
class VenueApplicationHttpSecurityTest {
    @Autowired MockMvc mvc;
    @Autowired JwtTokenProvider tokens;
    @MockitoBean CustomUserDetailsService users;
    @MockitoBean VenueApplicationSessionAccess access;
    @MockitoBean VenueApplicationSessionService sessions;
    @MockitoBean PushDeviceService devices;
    @MockitoBean PushDeviceRateLimit deviceLimit;
    @MockitoBean ListenerProfileChoiceGate listenerGate;
    @MockitoBean AuthRateLimitFilter authRateLimit;
    private final UUID application = UUID.randomUUID(), notification = UUID.randomUUID(), installation = UUID.randomUUID();
    private User user;
    private String bearer, base;

    @BeforeEach void prepareSignedApplicant() throws Exception {
        user = User.builder().id(UUID.randomUUID()).username("test-applicant").emailVerified(true)
                .status(UserStatus.PENDING_VENUE_REQUEST).roles(Set.of()).permissions(Set.of()).build();
        bearer = "Bearer " + tokens.generateVenueApplicationToken(new UserDetailsImpl(user), application);
        base = VenueApplicationRequestPolicy.BASE + application;
        when(users.loadUserById(user.getId())).thenReturn(new UserDetailsImpl(user));
        when(access.isAccessible(user, application)).thenReturn(true);
        doAnswer(invocation -> {
            ((FilterChain) invocation.getArgument(2)).doFilter(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(authRateLimit).doFilter(any(ServletRequest.class), any(ServletResponse.class), any(FilterChain.class));
    }

    @Test void statusAndExactNotificationLookupAreNoStoreAndNeverAcknowledge() throws Exception {
        mvc.perform(get(base).header("Authorization", bearer)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get(base + "/notifications/" + notification).header("Authorization", bearer)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));
        verify(sessions).detail(user.getId(), application);
        verify(sessions).notification(user.getId(), application, notification);
        verify(sessions, never()).read(any(), any(), any());
        verifyNoInteractions(devices);
    }

    @Test void exactAckAndReadOnlyDeliveryStateUseOwnApplicationAndIds() throws Exception {
        when(sessions.dismissed(user.getId(), application, List.of(notification))).thenReturn(List.of(notification));
        mvc.perform(post(base + "/notifications/" + notification + "/read").header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(post(base + "/notifications/delivery-state").header("Authorization", bearer)
                        .contentType("application/json").content("{\"notificationIds\":[\"" + notification + "\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.dismissedIds[0]").value(notification.toString()))
                .andExpect(header().string("Cache-Control", "no-store"));
        verify(sessions, times(1)).read(user.getId(), application, notification);
        verify(sessions).dismissed(user.getId(), application, List.of(notification));
        verifyNoInteractions(devices);
    }

    @Test void applicationEnrollmentAndRevocationUseRestrictedServiceMethodsOnly() throws Exception {
        mvc.perform(put(base + "/push/devices/" + installation).header("Authorization", bearer)
                        .contentType("application/json").content("""
                        {"token":"fictional-unit-test-token","platform":"ANDROID","permission":"AUTHORIZED",
                         "clientRevision":1,"presentationVersion":"ANDROID_NATIVE_V3"}
                        """))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(delete(base + "/push/devices/" + installation).queryParam("clientRevision", "2")
                        .header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        verify(devices).registerApplication(eq(user.getId()), eq(application), eq(installation),
                argThat(request -> request.clientRevision() == 1 && "ANDROID_NATIVE_V3".equals(request.presentationVersion())));
        verify(devices).revokeApplication(user.getId(), application, installation, 2L);
        verify(devices, never()).register(any(), any(), any());
        verify(devices, never()).revoke(any(), any(), any());
        verifyNoInteractions(sessions);
    }

    @Test void scopedPromotionReachesOnlyFreshSourceServiceEvenWhenUserAlreadyActive() throws Exception {
        user.setStatus(UserStatus.ACTIVE);
        user.setRoles(Set.of(Role.builder().name("ROLE_VENUE").build()));
        mvc.perform(post(base + "/promote").header("Authorization", bearer)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));
        verify(sessions).promote(user.getId(), application, user.getSessionVersion());
        verifyNoInteractions(devices);
    }

    @Test void activeOrdinaryVenueTokenCanUseOwnedStatusEndpoint() throws Exception {
        user.setStatus(UserStatus.ACTIVE);
        user.setRoles(Set.of(Role.builder().name("ROLE_VENUE").build()));
        String ordinary = "Bearer " + tokens.generateToken(new UserDetailsImpl(user));
        mvc.perform(get(base).header("Authorization", ordinary)).andExpect(status().isOk());
        verify(sessions).detail(user.getId(), application);
    }

    @Test void anonymousOrInvalidCurrentSourceCannotReachControllers() throws Exception {
        mvc.perform(get(base)).andExpect(status().isUnauthorized());
        when(access.isAccessible(user, application)).thenReturn(false);
        mvc.perform(get(base).header("Authorization", bearer)).andExpect(status().isUnauthorized());
        verifyNoInteractions(sessions, devices);
    }

    @Test void wrongApplicationIdCannotReachOwnedSourceController() throws Exception {
        mvc.perform(get(VenueApplicationRequestPolicy.BASE + UUID.randomUUID()).header("Authorization", bearer))
                .andExpect(status().isForbidden());
        verifyNoInteractions(sessions, devices, access);
    }

    @Test void wrongMethodExtraQueryAndPrefixDoNotReachController() throws Exception {
        mvc.perform(post(base).header("Authorization", bearer)).andExpect(status().isForbidden());
        mvc.perform(get(base).queryParam("applicationId", UUID.randomUUID().toString()).header("Authorization", bearer))
                .andExpect(status().isForbidden());
        mvc.perform(get("/prefix" + base).header("Authorization", bearer)).andExpect(status().isForbidden());
        mvc.perform(delete(base + "/push/devices/" + installation).queryParam("clientRevision", "1", "2")
                .header("Authorization", bearer)).andExpect(status().isForbidden());
        verifyNoInteractions(sessions, devices, access);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/user/notifications", "/api/v1/user/notifications/read-all", "/api/v1/user/dm",
            "/api/v1/auth/complete-google-profile", "/api/v1/auth/login", "/ws"})
    void approvedScopedBearerStillCannotReachGeneralOrPublicEndpoints(String path) throws Exception {
        user.setStatus(UserStatus.ACTIVE);
        user.setRoles(Set.of(Role.builder().name("ROLE_VENUE").build()));
        mvc.perform(post(path).header("Authorization", bearer)).andExpect(status().isForbidden());
        verifyNoInteractions(sessions, devices, access);
    }
}
