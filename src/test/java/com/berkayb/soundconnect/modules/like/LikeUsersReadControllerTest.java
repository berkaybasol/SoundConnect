package com.berkayb.soundconnect.modules.like;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.comment.dto.support.UserSummaryDto;
import com.berkayb.soundconnect.modules.engagement.enums.EngagementTargetType;
import com.berkayb.soundconnect.modules.like.controller.LikeUsersReadController;
import com.berkayb.soundconnect.modules.like.dto.LikeUsersPage;
import com.berkayb.soundconnect.modules.like.service.LikeUsersReadService;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = LikeUsersReadController.class, excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE, classes = com.berkayb.soundconnect.auth.security.JwtAuthenticationFilter.class))
@AutoConfigureMockMvc(addFilters = false)
@Import(LikeUsersReadControllerTest.MethodSecurity.class)
class LikeUsersReadControllerTest {
    @TestConfiguration @EnableMethodSecurity static class MethodSecurity { }
    @Autowired MockMvc mvc;
    @MockitoBean LikeUsersReadService service;
    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @Test void authenticatedReaderGetsPrivateEnvelopeAndGhostMarkersWithoutProfileIdentityLeaks() throws Exception {
        UUID viewer = authenticate(), target = UUID.randomUUID(), liker = UUID.randomUUID();
        when(service.get(viewer, EngagementTargetType.TABLE_GROUP_POST, target, 20, "opaque"))
                .thenReturn(new LikeUsersPage(List.of(new UserSummaryDto(liker, "canonical", null, ListenerVisibilityMode.GHOST)), "next", true));
        mvc.perform(get("/api/v1/likes/TABLE_GROUP_POST/" + target + "/users").param("size", "20").param("cursor", "opaque"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("Cache-Control", containsString("private")))
                .andExpect(jsonPath("$.data.items[0].id").value(liker.toString()))
                .andExpect(jsonPath("$.data.items[0].username").value("canonical"))
                .andExpect(jsonPath("$.data.items[0].visibilityMode").value("GHOST"))
                .andExpect(jsonPath("$.data.items[0].stageName").doesNotExist())
                .andExpect(jsonPath("$.data.nextCursor").value("next"))
                .andExpect(jsonPath("$.data.hasMore").value(true));
    }

    @Test void guestCannotReachTheService() throws Exception {
        mvc.perform(get("/api/v1/likes/MEDIA/" + UUID.randomUUID() + "/users"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test void invalidWireValuesCannotReachTheService() throws Exception {
        authenticate();
        mvc.perform(get("/api/v1/likes/UNKNOWN/" + UUID.randomUUID() + "/users")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/likes/MEDIA/not-a-uuid/users")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/likes/MEDIA/" + UUID.randomUUID() + "/users").param("size", "twenty"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    private UUID authenticate() {
        UUID id = UUID.randomUUID();
        User user = User.builder().id(id).username("viewer").password("unused").email("viewer@test.invalid")
                .emailVerified(true).status(UserStatus.ACTIVE).roles(Set.of(Role.builder().name("ROLE_MUSICIAN").build())).build();
        UserDetailsImpl principal = UserDetailsImpl.fromUser(user);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        return id;
    }
}
