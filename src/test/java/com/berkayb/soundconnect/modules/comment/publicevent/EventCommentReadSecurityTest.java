package com.berkayb.soundconnect.modules.comment.publicevent;

import com.berkayb.soundconnect.auth.ratelimit.AuthRateLimitFilter;
import com.berkayb.soundconnect.auth.security.JwtAuthenticationFilter;
import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.comment.controller.CommentController;
import com.berkayb.soundconnect.modules.comment.entity.Comment;
import com.berkayb.soundconnect.modules.comment.mapper.CommentMapper;
import com.berkayb.soundconnect.modules.comment.service.CommentService;
import com.berkayb.soundconnect.modules.engagement.enums.EngagementTargetType;
import com.berkayb.soundconnect.modules.event.controller.user.EventUserController;
import com.berkayb.soundconnect.modules.event.service.EventService;
import com.berkayb.soundconnect.modules.like.controller.LikeController;
import com.berkayb.soundconnect.modules.like.service.LikeService;
import com.berkayb.soundconnect.modules.like.dto.CommentLikeState;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.shared.config.SecurityConfig;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.RateLimitedException;
import com.berkayb.soundconnect.shared.exception.ServiceUnavailableRetryException;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.UUID;
import java.time.LocalDateTime;
import org.mapstruct.factory.Mappers;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({EventCommentReadController.class, CommentController.class, EventUserController.class,LikeController.class})
@Import({SecurityConfig.class, RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class,
        SecurityErrorResponseWriter.class})
class EventCommentReadSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean EventCommentReadService service;
    @MockitoBean CommentService comments;
    @MockitoBean EventService events;
    @MockitoBean com.berkayb.soundconnect.modules.event.support.EventScheduleClock eventClock;
    @MockitoBean LikeService likes;
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

    @Test
    void genericReplyPagesAndOwnDeleteUseAuthenticatedActorAndPrivateCachePolicy() throws Exception {
        UUID actor = UUID.randomUUID();
        when(comments.getReplies(eq(actor), eq(commentId), any())).thenReturn(Page.empty(PageRequest.of(2, 20)));
        mvc.perform(get("/api/v1/comments/replies/{id}", commentId).with(viewer(actor))
                        .param("page", "2").param("size", "20"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.number").value(2))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
        mvc.perform(delete("/api/v1/comments/{id}", commentId).with(viewer(actor))).andExpect(status().isOk());
        verify(comments).deleteComment(actor, commentId);
    }

    @ParameterizedTest
    @ValueSource(strings = {"EVENT", "OVERTHINKING", "MEDIA"})
    void burstRejectionPreservesTyped429ContractForRootAndReply(String target) throws Exception {
        UUID actor = UUID.randomUUID();
        var targetType = EngagementTargetType.valueOf(target);
        when(comments.createComment(eq(actor), eq(targetType), eq(eventId), any()))
                .thenThrow(new RateLimitedException(ErrorType.COMMENT_BURST_RATE_LIMITED, 17));
        for (String body : List.of("{\"text\":\"root\"}",
                "{\"text\":\"reply\",\"parentCommentId\":\"" + commentId + "\"}")) {
            mvc.perform(post("/api/v1/comments/{target}/{id}", target, eventId)
                            .with(viewer(actor)).contentType("application/json").content(body))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().string("Retry-After", "17"))
                    .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                    .andExpect(jsonPath("$.code").value(9356))
                    .andExpect(jsonPath("$.httpStatus").value("TOO_MANY_REQUESTS"))
                    .andExpect(jsonPath("$.details[0]").value(ErrorType.COMMENT_BURST_RATE_LIMITED.getDetails()))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }
        verify(comments, times(2)).createComment(eq(actor), eq(targetType), eq(eventId), any());
        verifyNoInteractions(service, events);
    }

    @ParameterizedTest
    @ValueSource(strings = {"EVENT", "OVERTHINKING", "MEDIA"})
    void unavailableGuardHasDistinctPrewriteContractForRootAndReply(String target) throws Exception {
        UUID actor = UUID.randomUUID();
        var targetType = EngagementTargetType.valueOf(target);
        when(comments.createComment(eq(actor), eq(targetType), eq(eventId), any()))
                .thenThrow(new ServiceUnavailableRetryException(ErrorType.COMMENT_BURST_UNAVAILABLE, 5));
        for (String body : List.of("{\"text\":\"root\"}",
                "{\"text\":\"reply\",\"parentCommentId\":\"" + commentId + "\"}")) {
            mvc.perform(post("/api/v1/comments/{target}/{id}", target, eventId)
                            .with(viewer(actor)).contentType("application/json").content(body))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Retry-After", "5"))
                    .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                    .andExpect(jsonPath("$.code").value(9357))
                    .andExpect(jsonPath("$.httpStatus").value("SERVICE_UNAVAILABLE"))
                    .andExpect(jsonPath("$.details[0]").value(ErrorType.COMMENT_BURST_UNAVAILABLE.getDetails()))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }
        verify(comments, times(2)).createComment(eq(actor), eq(targetType), eq(eventId), any());
        verifyNoInteractions(service, events);
    }

    @Test
    void blankAndOver500TextNeverReachMutationService() throws Exception {
        for (String text : List.of("  ", "a".repeat(501))) {
            mvc.perform(post("/api/v1/comments/MEDIA/{id}", eventId).with(viewer(UUID.randomUUID()))
                            .contentType("application/json").content("{\"text\":\"" + text + "\"}"))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(comments);
    }

    @Test
    void genericHttpDefaultPageSizeIsSpringTwentyAndCarriesMetadata() throws Exception {
        UUID actor = UUID.randomUUID();
        when(comments.getComments(eq(actor),eq(EngagementTargetType.MEDIA),eq(eventId),any()))
                .thenAnswer(invocation -> Page.empty(invocation.getArgument(3)));
        mvc.perform(get("/api/v1/comments/MEDIA/{id}",eventId).with(viewer(actor)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.number").value(0))
                .andExpect(jsonPath("$.data.size").value(20));
    }

    @ParameterizedTest
    @ValueSource(strings={"EVENT","MEDIA","OVERTHINKING"})
    void genericRootReplyAndCreateResponsesAlwaysIncludeExplicitUtcTimestamp(String target) throws Exception {
        UUID actor=UUID.randomUUID();
        var type=EngagementTargetType.valueOf(target);
        var root=timestampComment(type,null);
        var reply=timestampComment(type,root);
        var mapper=Mappers.getMapper(CommentMapper.class);
        var rootDto=mapper.toResolvedComment(root,1,true,null);
        var replyDto=mapper.toResolvedReply(reply,true,null);
        var createdReply=mapper.toResolvedComment(reply,0,true,null);
        when(comments.getComments(eq(actor),eq(type),eq(eventId),any())).thenReturn(new PageImpl<>(List.of(rootDto)));
        when(comments.getReplies(eq(actor),eq(root.getId()),any())).thenReturn(new PageImpl<>(List.of(replyDto)));
        when(comments.createComment(eq(actor),eq(type),eq(eventId),any())).thenReturn(rootDto,createdReply);
        mvc.perform(get("/api/v1/comments/{type}/{id}",target,eventId).with(viewer(actor)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content[0].createdAt").value("2026-09-08T20:32:10.123456Z"))
                .andExpect(jsonPath("$.data.content[0].likeCount").value(0)).andExpect(jsonPath("$.data.content[0].likedByMe").value(false));
        mvc.perform(get("/api/v1/comments/replies/{id}",root.getId()).with(viewer(actor)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content[0].createdAt").value("2026-09-08T20:32:10.123456Z"))
                .andExpect(jsonPath("$.data.content[0].likeCount").value(0)).andExpect(jsonPath("$.data.content[0].likedByMe").value(false));
        mvc.perform(post("/api/v1/comments/{type}/{id}",target,eventId).with(viewer(actor))
                        .contentType("application/json").content("{\"text\":\"root\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.createdAt").value("2026-09-08T20:32:10.123456Z"))
                .andExpect(jsonPath("$.data.likeCount").value(0)).andExpect(jsonPath("$.data.likedByMe").value(false));
        mvc.perform(post("/api/v1/comments/{type}/{id}",target,eventId).with(viewer(actor))
                        .contentType("application/json").content("{\"text\":\"reply\",\"parentCommentId\":\""+root.getId()+"\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.createdAt").value("2026-09-08T20:32:10.123456Z"))
                .andExpect(jsonPath("$.data.parentCommentId").value(root.getId().toString()))
                .andExpect(jsonPath("$.data.likeCount").value(0)).andExpect(jsonPath("$.data.likedByMe").value(false));
    }

    @Test
    void guestEventRootAndReplyResponsesAlwaysIncludeExplicitUtcTimestamp() throws Exception {
        var root=timestampComment(EngagementTargetType.EVENT,null);
        var reply=timestampComment(EngagementTargetType.EVENT,root);
        var mapper=Mappers.getMapper(CommentMapper.class);
        when(service.getComments(null,eventId,0,20)).thenReturn(new PageImpl<>(List.of(mapper.toResolvedComment(root,1,true,null))));
        when(service.getReplies(null,eventId,commentId,0,20)).thenReturn(new PageImpl<>(List.of(mapper.toResolvedReply(reply,true,null))));
        mvc.perform(get(commentsPath())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].createdAt").value("2026-09-08T20:32:10.123456Z"))
                .andExpect(jsonPath("$.data.content[0].likeCount").value(0)).andExpect(jsonPath("$.data.content[0].likedByMe").value(false));
        mvc.perform(get(repliesPath())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].createdAt").value("2026-09-08T20:32:10.123456Z"))
                .andExpect(jsonPath("$.data.content[0].likeCount").value(0)).andExpect(jsonPath("$.data.content[0].likedByMe").value(false));
    }

    private Comment timestampComment(EngagementTargetType type,Comment parent) {
        return Comment.builder().id(parent==null ? commentId : UUID.randomUUID()).targetType(type).targetId(eventId)
                .parentComment(parent).text("fresh").createdAt(LocalDateTime.of(2026,9,8,20,32,10,123456000)).build();
    }

    @Test void guestCannotMutateOrReadPrivateCommentLikeState() throws Exception {
        String path="/api/v1/likes/COMMENT/"+commentId;
        mvc.perform(post(path)).andExpect(status().isUnauthorized());
        mvc.perform(delete(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path+"/state")).andExpect(status().isUnauthorized());
        mvc.perform(get(path+"/count")).andExpect(status().isUnauthorized());
        mvc.perform(get(path+"/is-liked")).andExpect(status().isUnauthorized());
        verifyNoInteractions(likes);
    }

    @Test void commentLikeDesiredWritesAndReconciliationReturnAuthoritativePrivateSnapshots() throws Exception {
        UUID actor=UUID.randomUUID(); String path="/api/v1/likes/COMMENT/"+commentId;
        when(likes.setCommentLike(actor,commentId,true)).thenReturn(new CommentLikeState(12,true));
        when(likes.setCommentLike(actor,commentId,false)).thenReturn(new CommentLikeState(11,false));
        when(likes.readCommentLike(actor,commentId)).thenReturn(new CommentLikeState(11,false));
        mvc.perform(post(path).with(viewer(actor))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.likeCount").value(12)).andExpect(jsonPath("$.data.likedByMe").value(true))
                .andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store")));
        mvc.perform(delete(path).with(viewer(actor))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.likeCount").value(11)).andExpect(jsonPath("$.data.likedByMe").value(false));
        mvc.perform(get(path+"/state").with(viewer(actor))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.likeCount").value(11)).andExpect(jsonPath("$.data.likedByMe").value(false))
                .andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store")));
        verify(likes).setCommentLike(actor,commentId,true);
        verify(likes).setCommentLike(actor,commentId,false);
        verify(likes).readCommentLike(actor,commentId);
        verifyNoMoreInteractions(likes);
    }

    @ParameterizedTest @ValueSource(strings={"EVENT","MEDIA","OVERTHINKING"})
    void existingContentLikeEndpointsKeepTheirVoidContract(String type) throws Exception {
        UUID actor=UUID.randomUUID(); var target=EngagementTargetType.valueOf(type);
        String path="/api/v1/likes/"+type+"/"+eventId;
        mvc.perform(post(path).with(viewer(actor))).andExpect(status().isOk()).andExpect(jsonPath("$.data").doesNotExist());
        mvc.perform(delete(path).with(viewer(actor))).andExpect(status().isOk()).andExpect(jsonPath("$.data").doesNotExist());
        verify(likes).like(actor,target,eventId); verify(likes).unlike(actor,target,eventId);
        verify(likes,never()).setCommentLike(any(),any(),anyBoolean());
    }

    @Test void deletedOrHiddenCommentLikeResponsesDoNotExposeState() throws Exception {
        UUID actor=UUID.randomUUID(); String path="/api/v1/likes/COMMENT/"+commentId;
        when(likes.setCommentLike(actor,commentId,true)).thenThrow(new SoundConnectException(ErrorType.COMMENT_NOT_FOUND));
        when(likes.readCommentLike(actor,commentId)).thenThrow(new SoundConnectException(ErrorType.ENGAGEMENT_NOT_FOUND));
        mvc.perform(post(path).with(viewer(actor))).andExpect(status().isNotFound()).andExpect(jsonPath("$.data").doesNotExist());
        mvc.perform(get(path+"/state").with(viewer(actor))).andExpect(status().isNotFound()).andExpect(jsonPath("$.data").doesNotExist());
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
