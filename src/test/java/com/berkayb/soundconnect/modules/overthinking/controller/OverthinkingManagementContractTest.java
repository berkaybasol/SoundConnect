package com.berkayb.soundconnect.modules.overthinking.controller;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.overthinking.service.OverthinkingPostService;
import com.berkayb.soundconnect.modules.overthinking.service.OverthinkingPostCommandService;
import com.berkayb.soundconnect.modules.overthinking.service.OverthinkingRevealRequestService;
import com.berkayb.soundconnect.modules.overthinking.service.OverthinkingRevealInboxService;
import com.berkayb.soundconnect.modules.overthinking.dto.response.OverthinkingIncomingUnreadStatusResponseDto;
import com.berkayb.soundconnect.modules.overthinking.enums.OverthinkingFeedOrder;
import com.berkayb.soundconnect.modules.overthinking.profileshare.*;
import com.berkayb.soundconnect.shared.response.PageResponse;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.GlobalExceptionHandler;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {OverthinkingRevealRequestController.class, OverthinkingPostController.class, OverthinkingProfileShareController.class},
        properties = {"spring.config.location=classpath:/application-test.yml", "spring.config.import="})
@ContextConfiguration(classes = {OverthinkingRevealRequestController.class, OverthinkingPostController.class,
        OverthinkingProfileShareController.class, GlobalExceptionHandler.class, OverthinkingManagementContractTest.Security.class})
@AutoConfigureMockMvc(addFilters = false)
class OverthinkingManagementContractTest {
    @Autowired MockMvc mvc;
    @MockitoBean OverthinkingRevealRequestService reveals;
    @MockitoBean OverthinkingPostService posts;
    @MockitoBean OverthinkingPostCommandService commands;
    @MockitoBean OverthinkingRevealInboxService inbox;
    @MockitoBean OverthinkingProfileShareService shares;

    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @Test void pendingCountUsesAuthenticatedOwnerAndIgnoresClientSuppliedAuthor() throws Exception {
        UUID owner = UUID.randomUUID();
        authenticate(owner);
        when(reveals.getIncomingPendingRequestCount(owner)).thenReturn(31L);
        mvc.perform(get("/api/v1/overthinking/reveal-requests/incoming/pending-count")
                        .param("authorId", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.count").value(31));
        verify(reveals).getIncomingPendingRequestCount(owner);
        verifyNoMoreInteractions(reveals);
    }

    @Test void pendingCountRequiresAuthenticationBeforeCallingService() throws Exception {
        mvc.perform(get("/api/v1/overthinking/reveal-requests/incoming/pending-count"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(reveals);
    }

    @Test void legacyPutReceivesExplicitImmutableConflict() throws Exception {
        UUID owner = UUID.randomUUID(), post = UUID.randomUUID();
        var authentication = authenticate(owner);
        when(posts.update(eq(post), eq(owner), any())).thenThrow(new SoundConnectException(ErrorType.OVERTHINKING_POST_IMMUTABLE));
        mvc.perform(put("/api/v1/overthinking/" + post).principal(authentication)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"title":"Changed title","content":"Changed meaning","visibilityType":"VISIBLE"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(9412));
        verify(posts).update(eq(post), eq(owner), any());
    }

    @Test void unreadSnapshotAndAcknowledgementUseAuthenticatedAuthorAndReturnNewerArrival() throws Exception {
        UUID owner = UUID.randomUUID();
        authenticate(owner);
        when(inbox.getUnreadStatus(owner)).thenReturn(new OverthinkingIncomingUnreadStatusResponseDto(true, 5));
        when(inbox.markSeen(owner, 5)).thenReturn(new OverthinkingIncomingUnreadStatusResponseDto(true, 6));
        mvc.perform(get("/api/v1/overthinking/reveal-requests/incoming/unread-status")
                        .param("authorId", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasUnread").value(true))
                .andExpect(jsonPath("$.data.revision").value(5));
        mvc.perform(post("/api/v1/overthinking/reveal-requests/incoming/seen")
                        .contentType(APPLICATION_JSON)
                        .content("{\"revision\":5,\"authorId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasUnread").value(true))
                .andExpect(jsonPath("$.data.revision").value(6));
        verify(inbox).getUnreadStatus(owner);
        verify(inbox).markSeen(owner, 5);
        verifyNoMoreInteractions(inbox);
    }

    @Test void unreadAndSeenEndpointsRequireAuthentication() throws Exception {
        mvc.perform(get("/api/v1/overthinking/reveal-requests/incoming/unread-status"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/overthinking/reveal-requests/incoming/seen")
                        .contentType(APPLICATION_JSON).content("{\"revision\":5}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(inbox);
    }

    @Test void seenRequiresAnExplicitNonnegativeSnapshotRevision() throws Exception {
        authenticate(UUID.randomUUID());
        for (String body : List.of("{}", "{\"revision\":null}", "{\"revision\":-1}")) {
            mvc.perform(post("/api/v1/overthinking/reveal-requests/incoming/seen")
                            .contentType(APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(inbox);
    }

    @Test void withdrawalUsesOnlyAuthenticatedRequesterAndReturnsEmptySuccess() throws Exception {
        UUID requester = UUID.randomUUID(), postId = UUID.randomUUID();
        authenticate(requester);
        mvc.perform(delete("/api/v1/overthinking/" + postId + "/reveal-requests")
                        .param("requesterId", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").doesNotExist());
        verify(reveals).cancelRevealRequest(requester, postId);
        verifyNoMoreInteractions(reveals);
    }

    @Test void withdrawalRequiresAuthenticationAndDecidedRequestReturnsConflict() throws Exception {
        UUID requester = UUID.randomUUID(), postId = UUID.randomUUID();
        String path = "/api/v1/overthinking/" + postId + "/reveal-requests";
        mvc.perform(delete(path)).andExpect(status().isUnauthorized());
        verifyNoInteractions(reveals);
        authenticate(requester);
        doThrow(new SoundConnectException(ErrorType.OVERTHINKING_REVEAL_REQUEST_ALREADY_DECIDED))
                .when(reveals).cancelRevealRequest(requester, postId);
        mvc.perform(delete(path)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(9409));
        when(reveals.createRevealRequest(requester, postId))
                .thenThrow(new SoundConnectException(ErrorType.OVERTHINKING_REVEAL_REQUEST_ALREADY_DECIDED));
        mvc.perform(post(path)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(9409));
    }

    @Test void feedOrderAcceptsOnlyDeclaredOrdersAndDefaultsToNewest() throws Exception {
        UUID viewer = UUID.randomUUID();
        var authentication = authenticate(viewer);
        when(posts.getAll(eq(viewer), any(Pageable.class), any(OverthinkingFeedOrder.class)))
                .thenReturn(Page.empty());
        mvc.perform(get("/api/v1/overthinking/feed").principal(authentication))
                .andExpect(status().isOk());
        verify(posts).getAll(eq(viewer), any(Pageable.class), eq(OverthinkingFeedOrder.NEWEST));
        for (var order : List.of(OverthinkingFeedOrder.MOST_LIKED, OverthinkingFeedOrder.OLDEST)) {
            mvc.perform(get("/api/v1/overthinking/feed").principal(authentication)
                            .param("order", order.name()).param("page", "2").param("size", "3"))
                    .andExpect(status().isOk());
            verify(posts).getAll(eq(viewer), argThat(page -> page.getPageNumber() == 2 && page.getPageSize() == 3), eq(order));
        }
        mvc.perform(get("/api/v1/overthinking/feed").param("order", "unknown"))
                .andExpect(status().isBadRequest());
        verifyNoMoreInteractions(posts);
    }

    @Test void profileShareStatePublishExactDeleteAndPublicPaginationHaveUnambiguousRoutes() throws Exception {
        UUID owner = UUID.randomUUID(), postId = UUID.randomUUID(), shareId = UUID.randomUUID(), profileId = UUID.randomUUID();
        authenticate(owner);
        var snapshot = new OverthinkingProfileShareResponse.State(postId, shareId, true, "A note", Instant.parse("2026-09-10T12:00:00Z"), true);
        when(shares.get(owner, postId)).thenReturn(snapshot);
        when(shares.publish(eq(owner), eq(postId), any())).thenReturn(snapshot);
        when(shares.list(owner, profileId, 1, 3)).thenReturn(new PageResponse<>(List.of(), 1, 3, 0, 0, false, true));
        String path = "/api/v1/overthinking/" + postId + "/profile-share";
        mvc.perform(get(path).param("owner", UUID.randomUUID().toString())).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$.data.postId").value(postId.toString()))
                .andExpect(jsonPath("$.data.shareId").value(shareId.toString()))
                .andExpect(jsonPath("$.data.canPublish").value(true));
        mvc.perform(put(path).contentType(APPLICATION_JSON).content("{\"note\":\"A note\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.note").value("A note"));
        mvc.perform(delete("/api/v1/overthinking/profile-shares/" + shareId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist());
        mvc.perform(get("/api/v1/public/listener-profiles/" + profileId + "/overthinking-posts")
                        .param("page", "1").param("size", "3")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isArray()).andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.number").value(1)).andExpect(jsonPath("$.data.size").value(3));
        verify(shares).get(owner, postId);
        verify(shares).publish(owner, postId, new OverthinkingProfileShareUpdate("A note"));
        verify(shares).delete(owner, shareId);
        verify(shares).list(owner, profileId, 1, 3);
        verifyNoInteractions(posts, reveals);
    }

    @Test void everyProfileShareRouteRequiresAuthenticationBeforeService() throws Exception {
        String post = "/api/v1/overthinking/" + UUID.randomUUID() + "/profile-share";
        mvc.perform(get(post)).andExpect(status().isUnauthorized());
        mvc.perform(put(post).contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/v1/overthinking/profile-shares/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/public/listener-profiles/" + UUID.randomUUID() + "/overthinking-posts"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(shares);
    }

    @Test void profileShareCommandRejectsActorFieldsCoercionUnknownFieldsAndDuplicateKeys() throws Exception {
        authenticate(UUID.randomUUID());
        String path = "/api/v1/overthinking/" + UUID.randomUUID() + "/profile-share";
        for (String body : List.of("[]", "null", "{\"note\":3}", "{\"note\":false}", "{\"postId\":\"x\"}",
                "{\"note\":null,\"ownerId\":\"x\"}", "{\"note\":\"first\",\"note\":\"second\"}")) {
            mvc.perform(put(path).contentType(APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(shares);
    }

    @Test void profileShareOptionalNullNoteAndDomainErrorsKeepExplicitContract() throws Exception {
        UUID owner = UUID.randomUUID(), source = UUID.randomUUID(); authenticate(owner);
        String path = "/api/v1/overthinking/" + source + "/profile-share";
        when(shares.publish(owner, source, new OverthinkingProfileShareUpdate(null)))
                .thenReturn(new OverthinkingProfileShareResponse.State(source, UUID.randomUUID(), true, null, Instant.now(), true));
        for (String body : List.of("{}", "{\"note\":null}")) {
            mvc.perform(put(path).contentType(APPLICATION_JSON).content(body)).andExpect(status().isOk());
        }
        when(shares.publish(owner, source, new OverthinkingProfileShareUpdate("different")))
                .thenThrow(new SoundConnectException(ErrorType.OVERTHINKING_PROFILE_SHARE_ALREADY_EXISTS));
        mvc.perform(put(path).contentType(APPLICATION_JSON).content("{\"note\":\"different\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(9413));
        when(shares.get(owner, source)).thenThrow(new SoundConnectException(ErrorType.FORBIDDEN_ACCESS));
        mvc.perform(get(path)).andExpect(status().isForbidden());
    }

    private UsernamePasswordAuthenticationToken authenticate(UUID id) {
        var authentication = new UsernamePasswordAuthenticationToken(
                UserDetailsImpl.fromUser(User.builder().id(id).username("test-user").build()), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);
        return authentication;
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class Security { }
}
