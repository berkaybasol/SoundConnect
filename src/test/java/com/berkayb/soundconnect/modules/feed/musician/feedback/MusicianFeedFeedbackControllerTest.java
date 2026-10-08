package com.berkayb.soundconnect.modules.feed.musician.feedback;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.feed.musician.abuse.MusicianFeedRateLimitGuard;
import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedFeedbackAction;
import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedFeedbackRequest;
import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedFeedbackResponse;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.RateLimitedException;
import com.berkayb.soundconnect.shared.exception.ServiceUnavailableRetryException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;
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
import java.util.Set;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = MusicianFeedFeedbackController.class, excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = com.berkayb.soundconnect.auth.security.JwtAuthenticationFilter.class))
@AutoConfigureMockMvc(addFilters = false)
@Import(MusicianFeedFeedbackControllerTest.MethodSecurity.class)
class MusicianFeedFeedbackControllerTest {
    private static final UUID PROFILE_ID = UUID.fromString("f3ff6f29-d8e7-471f-90b1-a0635d376969");
    private static final String ITEM_ID = "TRACK:" + PROFILE_ID;
    private static final MusicianFeedFeedbackRequest FEEDBACK_REQUEST = new MusicianFeedFeedbackRequest(
            MusicianFeedFeedbackAction.HIDE, null, "signed.impression.token");

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurity { }

    @Autowired MockMvc mvc;
    @MockitoBean MusicianFeedFeedbackService feedback;
    @MockitoBean MusicianFeedMutedAuthorsService mutedAuthors;
    @MockitoBean MusicianFeedRateLimitGuard rateLimitGuard;

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @EnumSource(Write.class)
    void everyWriteReservesTheAuthenticatedAccountBudgetBeforeMutation(Write write) throws Exception {
        UUID viewerId = authenticate("ROLE_MUSICIAN");
        var response = new MusicianFeedFeedbackResponse(UUID.randomUUID(),
                write == Write.FEEDBACK ? MusicianFeedFeedbackAction.HIDE : MusicianFeedFeedbackAction.MUTE_AUTHOR,
                write == Write.FEEDBACK ? ITEM_ID : null, "MUSICIAN", PROFILE_ID,
                Instant.parse("2026-09-13T12:00:00Z"));
        if (write == Write.FEEDBACK) {
            when(feedback.recordItem(viewerId, ITEM_ID, FEEDBACK_REQUEST)).thenReturn(response);
        } else if (write == Write.MUTE) {
            when(feedback.mute(viewerId, "MUSICIAN", PROFILE_ID)).thenReturn(response);
        }

        mvc.perform(request(write))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value(200));

        InOrder boundaryOrder = inOrder(rateLimitGuard, feedback);
        boundaryOrder.verify(rateLimitGuard).checkFeedback(viewerId);
        switch (write) {
            case FEEDBACK -> boundaryOrder.verify(feedback).recordItem(viewerId, ITEM_ID, FEEDBACK_REQUEST);
            case MUTE -> boundaryOrder.verify(feedback).mute(viewerId, "MUSICIAN", PROFILE_ID);
            case UNMUTE -> boundaryOrder.verify(feedback).unmute(viewerId, "MUSICIAN", PROFILE_ID);
        }
        verifyNoMoreInteractions(rateLimitGuard, feedback);
        verifyNoInteractions(mutedAuthors);
    }

    @ParameterizedTest
    @EnumSource(Write.class)
    void exhaustedBudgetReturns429WithRetryAdviceAndDoesNotMutate(Write write) throws Exception {
        UUID viewerId = authenticate("ROLE_MUSICIAN");
        doThrow(new RateLimitedException(ErrorType.MUSICIAN_FEED_RATE_LIMITED, 2L))
                .when(rateLimitGuard).checkFeedback(viewerId);

        mvc.perform(request(write))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "2"))
                .andExpect(jsonPath("$.code").value(1319))
                .andExpect(jsonPath("$.httpStatus").value("TOO_MANY_REQUESTS"))
                .andExpect(jsonPath("$.message").value(ErrorType.MUSICIAN_FEED_RATE_LIMITED.getMessage()));

        verify(rateLimitGuard).checkFeedback(viewerId);
        verifyNoInteractions(feedback, mutedAuthors);
    }

    @ParameterizedTest
    @EnumSource(Write.class)
    void unavailableRedisProtectionReturns503WithRetryAdviceAndDoesNotMutate(Write write) throws Exception {
        UUID viewerId = authenticate("ROLE_MUSICIAN");
        doThrow(new ServiceUnavailableRetryException(ErrorType.MUSICIAN_FEED_RATE_LIMIT_UNAVAILABLE, 5L))
                .when(rateLimitGuard).checkFeedback(viewerId);

        mvc.perform(request(write))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.code").value(1320))
                .andExpect(jsonPath("$.httpStatus").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value(ErrorType.MUSICIAN_FEED_RATE_LIMIT_UNAVAILABLE.getMessage()));

        verify(rateLimitGuard).checkFeedback(viewerId);
        verifyNoInteractions(feedback, mutedAuthors);
    }

    @ParameterizedTest
    @EnumSource(Write.class)
    void wrongRoleAndAnonymousRequestsCannotSpendAnyAccountBudgetOrMutate(Write write) throws Exception {
        authenticate("ROLE_LISTENER");
        mvc.perform(request(write)).andExpect(status().isForbidden());
        SecurityContextHolder.clearContext();
        mvc.perform(request(write)).andExpect(status().isUnauthorized());

        verifyNoInteractions(rateLimitGuard, feedback, mutedAuthors);
    }

    private MockHttpServletRequestBuilder request(Write write) {
        return switch (write) {
            case FEEDBACK -> post("/api/v1/feed/musician/items/{id}/feedback", ITEM_ID)
                    .contentType("application/json")
                    .content("""
                            {"action":"HIDE","impressionToken":"signed.impression.token"}
                            """);
            case MUTE -> put("/api/v1/feed/musician/authors/MUSICIAN/{id}/mute", PROFILE_ID);
            case UNMUTE -> delete("/api/v1/feed/musician/authors/MUSICIAN/{id}/mute", PROFILE_ID);
        };
    }

    private UUID authenticate(String roleName) {
        UUID id = UUID.randomUUID();
        User user = User.builder().id(id).username("feedback_owner").password("unused")
                .email("feedback-owner@example.test").emailVerified(true).status(UserStatus.ACTIVE)
                .roles(Set.of(Role.builder().name(roleName).build())).build();
        UserDetailsImpl principal = UserDetailsImpl.fromUser(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        return id;
    }

    private enum Write { FEEDBACK, MUTE, UNMUTE }
}
