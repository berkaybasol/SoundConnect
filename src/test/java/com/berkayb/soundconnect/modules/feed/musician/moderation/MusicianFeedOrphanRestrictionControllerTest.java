package com.berkayb.soundconnect.modules.feed.musician.moderation;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.role.entity.Permission;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static com.berkayb.soundconnect.modules.feed.musician.moderation.MusicianFeedOrphanRestrictionModels.*;

@WebMvcTest(controllers = MusicianFeedOrphanRestrictionController.class,
        properties = "app.feed.musician.enabled=false", excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE, classes = com.berkayb.soundconnect.auth.security.JwtAuthenticationFilter.class))
@AutoConfigureMockMvc(addFilters = false)
@Import(MusicianFeedOrphanRestrictionControllerTest.MethodSecurity.class)
class MusicianFeedOrphanRestrictionControllerTest {
    private static final String BASE = "/api/v1/admin/musician-feed/restrictions";
    private static final UUID REPORT = UUID.randomUUID();
    private static final UUID REQUEST = UUID.randomUUID();
    private static final Instant UPDATED = Instant.parse("2026-09-13T12:00:00.123456Z");
    @TestConfiguration @EnableMethodSecurity static class MethodSecurity { }
    @Autowired MockMvc mvc;
    @MockitoBean MusicianFeedOrphanRestrictionService service;

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void requiresExplicitAuthorityForQueueAndRestore(boolean restore) throws Exception {
        authenticate("ROLE_ADMIN", false);
        mvc.perform(request(restore)).andExpect(status().isForbidden());
        authenticate("ROLE_MUSICIAN", false);
        mvc.perform(request(restore)).andExpect(status().isForbidden());
        SecurityContextHolder.clearContext();
        mvc.perform(request(restore)).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void authorizedResponsesArePrivateWhenFeedServingIsDisabled(boolean restore) throws Exception {
        UUID actor = authenticate("ROLE_ADMIN", true);
        when(service.list(actor, null, null)).thenReturn(new Page(List.of(), null, false));
        when(service.restore(eq(actor), eq(REPORT), any())).thenReturn(new Restored(REPORT, false, UPDATED, true));
        mvc.perform(request(restore)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("Cache-Control", containsString("private")));
        if (restore) verify(service).restore(actor, REPORT, new RestoreRequest(REQUEST, UPDATED, "Gerekçeli geri alma."));
        else verify(service).list(actor, null, null);
    }

    @Test
    void malformedWriteCannotReachServiceAndConflictRemains409() throws Exception {
        UUID actor = authenticate("ROLE_ADMIN", true);
        mvc.perform(post(BASE + "/{id}/restore", REPORT).contentType("application/json")
                .content("{\"clientRequestId\":\"" + REQUEST + "\",\"resolutionNote\":\"Gerekçe\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
        when(service.restore(eq(actor), eq(REPORT), any())).thenThrow(new SoundConnectException(ErrorType.MUSICIAN_FEED_REPORT_CONFLICT));
        mvc.perform(request(true)).andExpect(status().isConflict());
    }

    private MockHttpServletRequestBuilder request(boolean restore) {
        return restore ? post(BASE + "/{id}/restore", REPORT).contentType("application/json").content(
                "{\"clientRequestId\":\"" + REQUEST + "\",\"expectedUpdatedAt\":\"" + UPDATED
                        + "\",\"resolutionNote\":\"Gerekçeli geri alma.\"}") : get(BASE);
    }

    private UUID authenticate(String role, boolean allowed) {
        UUID id = UUID.randomUUID();
        Set<Permission> permissions = allowed
                ? Set.of(Permission.builder().id(UUID.randomUUID()).name("MANAGE_MUSICIAN_FEED_REPORTS").build()) : Set.of();
        var user = User.builder().id(id).username("moderator").password("unused").email("moderator@example.test")
                .emailVerified(true).status(UserStatus.ACTIVE)
                .roles(Set.of(Role.builder().id(UUID.randomUUID()).name(role).permissions(permissions).build())).build();
        var principal = UserDetailsImpl.fromUser(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        return id;
    }
}
