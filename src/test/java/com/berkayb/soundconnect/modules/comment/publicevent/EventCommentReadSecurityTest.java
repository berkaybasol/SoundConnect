package com.berkayb.soundconnect.modules.comment.publicevent;

import com.berkayb.soundconnect.auth.ratelimit.AuthRateLimitFilter;
import com.berkayb.soundconnect.auth.security.JwtAuthenticationFilter;
import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.comment.controller.CommentController;
import com.berkayb.soundconnect.modules.comment.service.CommentService;
import com.berkayb.soundconnect.modules.engagement.enums.EngagementTargetType;
import com.berkayb.soundconnect.modules.event.controller.user.EventUserController;
import com.berkayb.soundconnect.modules.event.service.EventService;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.shared.config.SecurityConfig;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.security.RestAccessDeniedHandler;
import com.berkayb.soundconnect.shared.security.RestAuthenticationEntryPoint;
import com.berkayb.soundconnect.shared.security.SecurityErrorResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({EventCommentReadController.class, CommentController.class, EventUserController.class})
@Import({SecurityConfig.class, RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class,
        SecurityErrorResponseWriter.class})
class EventCommentReadSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean EventCommentReadService service;
    @MockitoBean CommentService comments;
    @MockitoBean EventService events;
    @MockitoBean JwtAuthenticationFilter jwt;
    @MockitoBean AuthRateLimitFilter rateLimit;
    private final UUID eventId = UUID.randomUUID(), commentId = UUID.randomUUID();

    @BeforeEach
    void passThroughOnlyApplicationFilters() throws Exception {
        doAnswer(invocation -> {
            FilterChain chain = invocation.getArgument(2);
            chain.doFilter(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(jwt).doFilter(any(ServletRequest.class), any(ServletResponse.class), any(FilterChain.class));
        doAnswer(invocation -> {
            FilterChain chain = invocation.getArgument(2);
            chain.doFilter(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(rateLimit).doFilter(any(ServletRequest.class), any(ServletResponse.class), any(FilterChain.class));
    }

    @Test
    void guestCanReadEventDetailCommentsAndRepliesWithoutCredentials() throws Exception {
        when(service.getComments(null, eventId, 0, 20)).thenReturn(Page.empty(PageRequest.of(0, 20)));
        when(service.getReplies(null, eventId, commentId, 0, 20)).thenReturn(Page.empty(PageRequest.of(0, 20)));
        mvc.perform(get("/api/v1/events/{eventId}", eventId)).andExpect(status().isOk());
        mvc.perform(get(commentsPath())).andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data.content").isEmpty())
                .andExpect(jsonPath("$.data.number").value(0)).andExpect(jsonPath("$.data.size").value(20));
        mvc.perform(get(repliesPath())).andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data.content").isEmpty());
        verify(events).getEventById(eventId);
        verify(service).getComments(null, eventId, 0, 20);
        verify(service).getReplies(null, eventId, commentId, 0, 20);
        verifyNoInteractions(comments);
    }

    @Test
    void authenticatedViewerAndExplicitPageReachBothPublicReadAdapters() throws Exception {
        UUID viewer = UUID.randomUUID();
        when(service.getComments(viewer, eventId, 3, 50)).thenReturn(Page.empty(PageRequest.of(3, 50)));
        when(service.getReplies(viewer, eventId, commentId, 3, 50)).thenReturn(Page.empty(PageRequest.of(3, 50)));
        mvc.perform(get(commentsPath()).param("page", "3").param("size", "50").with(viewer(viewer)))
                .andExpect(status().isOk());
        mvc.perform(get(repliesPath()).param("page", "3").param("size", "50").with(viewer(viewer)))
                .andExpect(status().isOk());
        verify(service).getComments(viewer, eventId, 3, 50);
        verify(service).getReplies(viewer, eventId, commentId, 3, 50);
    }

    @ParameterizedTest
    @CsvSource({"page,-1", "page,1001", "page,2147483648", "page,nope", "size,0", "size,51", "size,nope"})
    void malformedOrUnboundedPagingNeverReachesReadService(String parameter, String value) throws Exception {
        mvc.perform(get(commentsPath()).param(parameter, value)).andExpect(status().isBadRequest());
        mvc.perform(get(repliesPath()).param(parameter, value)).andExpect(status().isBadRequest());
        verifyNoInteractions(service, comments, events);
    }

    @Test
    void invalidIdentifiersReturnBadRequestWithoutAnyRead() throws Exception {
        mvc.perform(get("/api/v1/events/not-a-uuid/comments")).andExpect(status().isBadRequest());
        mvc.perform(get(commentsPath() + "/not-a-uuid/replies")).andExpect(status().isBadRequest());
        verifyNoInteractions(service, comments, events);
    }

    @Test
    void eligibilityAndParentFailuresKeepTheirNotFoundEnvelope() throws Exception {
        when(service.getComments(null, eventId, 0, 20)).thenThrow(new SoundConnectException(ErrorType.EVENT_NOT_FOUND));
        when(service.getReplies(null, eventId, commentId, 0, 20))
                .thenThrow(new SoundConnectException(ErrorType.COMMENT_NOT_FOUND));
        mvc.perform(get(commentsPath())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorType.EVENT_NOT_FOUND.getCode()));
        mvc.perform(get(repliesPath())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorType.COMMENT_NOT_FOUND.getCode()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"EVENT", "OVERTHINKING", "MEDIA"})
    void anonymousGenericCommentAndReplyCreationCannotReachService(String target) throws Exception {
        String path = "/api/v1/comments/" + target + "/" + eventId;
        mvc.perform(post(path).contentType("application/json").content("{\"text\":\"Hello\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(path).contentType("application/json")
                        .content("{\"text\":\"Reply\",\"parentCommentId\":\"" + commentId + "\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service, comments, events);
    }

    @Test
    void genericReadsDeletesAndNewNamespaceMutationsRemainAuthenticated() throws Exception {
        for (String target : List.of("EVENT", "OVERTHINKING", "MEDIA")) {
            mvc.perform(get("/api/v1/comments/" + target + "/" + eventId)).andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/api/v1/comments/replies/" + commentId)).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/v1/comments/" + commentId)).andExpect(status().isUnauthorized());
        mvc.perform(post(commentsPath())).andExpect(status().isUnauthorized());
        mvc.perform(post(repliesPath())).andExpect(status().isUnauthorized());
        mvc.perform(delete(commentsPath())).andExpect(status().isUnauthorized());
        verifyNoInteractions(service, comments, events);
    }

    @Test
    void authenticatedCommentAndReplyWritesStillUseExistingActorAndRoute() throws Exception {
        UUID userId = UUID.randomUUID();
        String path = "/api/v1/comments/EVENT/" + eventId;
        mvc.perform(post(path).with(viewer(userId)).contentType("application/json")
                        .content("{\"text\":\"Hello\"}"))
                .andExpect(status().isOk());
        mvc.perform(post(path).with(viewer(userId)).contentType("application/json")
                        .content("{\"text\":\"Reply\",\"parentCommentId\":\"" + commentId + "\"}"))
                .andExpect(status().isOk());
        verify(comments).createComment(eq(userId), eq(EngagementTargetType.EVENT), eq(eventId),
                argThat(request -> request.parentCommentId() == null && request.text().equals("Hello")));
        verify(comments).createComment(eq(userId), eq(EngagementTargetType.EVENT), eq(eventId),
                argThat(request -> commentId.equals(request.parentCommentId()) && request.text().equals("Reply")));
        verifyNoInteractions(service, events);
    }

    private String commentsPath() { return "/api/v1/events/" + eventId + "/comments"; }
    private String repliesPath() { return commentsPath() + "/" + commentId + "/replies"; }

    private RequestPostProcessor viewer(UUID id) {
        var user = User.builder().username("viewer").build();
        user.setId(id);
        return authentication(UsernamePasswordAuthenticationToken.authenticated(new UserDetailsImpl(user), null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }
}
