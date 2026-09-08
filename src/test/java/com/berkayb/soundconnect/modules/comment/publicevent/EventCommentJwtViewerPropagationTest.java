package com.berkayb.soundconnect.modules.comment.publicevent;

import com.berkayb.soundconnect.auth.ratelimit.AuthRateLimitFilter;
import com.berkayb.soundconnect.auth.security.*;
import com.berkayb.soundconnect.auth.service.CustomUserDetailsService;
import com.berkayb.soundconnect.modules.comment.controller.CommentController;
import com.berkayb.soundconnect.modules.comment.dto.response.CommentReplyResponseDto;
import com.berkayb.soundconnect.modules.comment.dto.response.CommentResponseDto;
import com.berkayb.soundconnect.modules.comment.service.CommentService;
import com.berkayb.soundconnect.modules.engagement.enums.EngagementTargetType;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.support.ListenerProfileChoiceStatusReader;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.shared.config.SecurityConfig;
import com.berkayb.soundconnect.shared.security.*;
import com.berkayb.soundconnect.shared.util.JwtUtil;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real Bearer parsing/security/controller propagation; no application server, database or real credentials. */
@WebMvcTest(controllers={EventCommentReadController.class,CommentController.class},properties={
        "spring.config.location=classpath:/application-test.yml","spring.config.import=",
        "app.jwt.secret=comment-read-regression-only-synthetic-signing-key",
        "app.jwt.expiration=600000","app.jwt.issuer=comment-read-regression"})
@ActiveProfiles("test")
@Import({SecurityConfig.class,JwtTokenProvider.class,JwtUtil.class,JwtAuthenticationFilter.class,
        ListenerProfileChoiceGate.class,RestAuthenticationEntryPoint.class,RestAccessDeniedHandler.class,
        SecurityErrorResponseWriter.class})
class EventCommentJwtViewerPropagationTest {
    @Autowired MockMvc mvc;
    @Autowired JwtTokenProvider tokens;
    @MockitoBean CustomUserDetailsService users;
    @MockitoBean ListenerProfileChoiceStatusReader listenerChoice;
    @MockitoBean EventCommentReadService publicComments;
    @MockitoBean CommentService genericComments;
    @MockitoBean AuthRateLimitFilter authRateLimit;
    final UUID eventId=UUID.randomUUID(),rootId=UUID.randomUUID(),replyId=UUID.randomUUID();
    final UserDetailsImpl liker=principal(),other=principal();

    @BeforeEach void rateLimitPassThrough() throws Exception {
        doAnswer(call -> {
            FilterChain chain=call.getArgument(2);
            chain.doFilter(call.getArgument(0),call.getArgument(1));
            return null;
        }).when(authRateLimit).doFilter(any(ServletRequest.class),any(ServletResponse.class),any(FilterChain.class));
        // Deliberately viewer-sensitive: losing the Bearer identity reproduces the live symptom.
        when(publicComments.getComments(nullable(UUID.class),eq(eventId),eq(0),eq(20)))
                .thenAnswer(call -> new PageImpl<>(List.of(root(call.getArgument(0))),PageRequest.of(0,20),1));
        when(publicComments.getReplies(nullable(UUID.class),eq(eventId),eq(rootId),eq(0),eq(20)))
                .thenAnswer(call -> new PageImpl<>(List.of(reply(call.getArgument(0))),PageRequest.of(0,20),1));
    }

    @Test void repeatedPublicEventReadsWithRealBearerRetainLikerIdentityForRootsAndReplies() throws Exception {
        when(users.loadUserById(liker.getId())).thenReturn(liker);
        String token=tokens.generateToken(liker);
        for(int reopening=0;reopening<3;reopening++) assertPublicState(token,true);
        verify(publicComments,times(3)).getComments(liker.getId(),eventId,0,20);
        verify(publicComments,times(3)).getReplies(liker.getId(),eventId,rootId,0,20);
        verify(users,times(6)).loadUserById(liker.getId());
    }

    @Test void omittingBearerPreservesCountsButIntentionallyLosesPersonalLikedState() throws Exception {
        assertPublicState(null,false);
        verify(publicComments).getComments(null,eventId,0,20);
        verify(publicComments).getReplies(null,eventId,rootId,0,20);
        verifyNoInteractions(users,listenerChoice);
    }

    @Test void switchingAccountsThenGuestThenOriginalAccountCannotReuseAnotherViewerState() throws Exception {
        when(users.loadUserById(liker.getId())).thenReturn(liker);
        when(users.loadUserById(other.getId())).thenReturn(other);
        String likerToken=tokens.generateToken(liker),otherToken=tokens.generateToken(other);
        assertPublicState(likerToken,true);
        assertPublicState(otherToken,false);
        assertPublicState(null,false);
        assertPublicState(likerToken,true);
        verify(publicComments).getComments(other.getId(),eventId,0,20);
        verify(publicComments).getReplies(other.getId(),eventId,rootId,0,20);
    }

    @ParameterizedTest @ValueSource(strings={"malformed","expired"})
    void unusableBearerIsAnonymousOnlyOnPublicReads(String kind) throws Exception {
        String token=kind.equals("malformed") ? "not-a-valid-jwt" : Jwts.builder()
                .setSubject(liker.getId().toString()).setIssuer("comment-read-regression")
                .setExpiration(Date.from(Instant.parse("2000-01-01T00:00:00Z")))
                .signWith(Keys.hmacShaKeyFor("comment-read-regression-only-synthetic-signing-key".getBytes(StandardCharsets.UTF_8)),
                        SignatureAlgorithm.HS256).compact();
        assertPublicState(token,false);
        for(String path : List.of("/api/v1/comments/EVENT/"+eventId,"/api/v1/comments/replies/"+rootId)) {
            mvc.perform(get(path).header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(users,genericComments);
    }

    @ParameterizedTest @ValueSource(strings={"inactive","unverified"})
    void freshAccountEligibilityStillControlsValidSignedTokens(String kind) throws Exception {
        String token=tokens.generateToken(liker);
        if(kind.equals("inactive")) liker.getUser().setStatus(UserStatus.INACTIVE);
        else liker.getUser().setEmailVerified(false);
        when(users.loadUserById(liker.getId())).thenReturn(liker);
        assertPublicState(token,false);
        mvc.perform(get("/api/v1/comments/EVENT/"+eventId).header("Authorization","Bearer "+token))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(genericComments);
    }

    @ParameterizedTest @ValueSource(strings={"ROLE_LISTENER","ROLE_MUSICIAN","ROLE_VENUE","ROLE_STUDIO","ROLE_ORGANIZER","ROLE_PRODUCER","ROLE_ADMIN"})
    void authenticatedGenericFallbackKeepsTheSameViewerAndRequiresBearerOnBothRoutes(String role) throws Exception {
        liker.getUser().setRoles(Set.of(Role.builder().name(role).build()));
        when(users.loadUserById(liker.getId())).thenReturn(liker);
        when(genericComments.getComments(eq(liker.getId()),eq(EngagementTargetType.EVENT),eq(eventId),any()))
                .thenReturn(new PageImpl<>(List.of(root(liker.getId()))));
        when(genericComments.getReplies(eq(liker.getId()),eq(rootId),any()))
                .thenReturn(new PageImpl<>(List.of(reply(liker.getId()))));
        String token=tokens.generateToken(liker);
        for(String path : List.of("/api/v1/comments/EVENT/"+eventId,"/api/v1/comments/replies/"+rootId)) {
            assertPage(get(path).header("Authorization","Bearer "+token),true);
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
    }

    @Test void pendingListenerChoiceCannotBeSilentlyDowngradedToGuestOnPublicReads() throws Exception {
        when(users.loadUserById(liker.getId())).thenReturn(liker);
        when(listenerChoice.requiresChoice(liker.getUser())).thenReturn(true);
        mvc.perform(get(publicRootPath()).header("Authorization","Bearer "+tokens.generateToken(liker)))
                .andExpect(status().isPreconditionRequired());
        verifyNoInteractions(publicComments,genericComments);
    }

    private void assertPublicState(String token,boolean liked) throws Exception {
        for(String path : List.of(publicRootPath(),publicRootPath()+"/"+rootId+"/replies")) {
            var request=get(path);
            if(token!=null) request.header("Authorization","Bearer "+token);
            assertPage(request,liked);
        }
    }
    private void assertPage(MockHttpServletRequestBuilder request,boolean liked) throws Exception {
        mvc.perform(request).andExpect(status().isOk()).andExpect(jsonPath("$.data.content[0].likeCount").value(3))
                .andExpect(jsonPath("$.data.content[0].likedByMe").value(liked))
                .andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store")));
    }
    private String publicRootPath() { return "/api/v1/events/"+eventId+"/comments"; }
    private CommentResponseDto root(UUID viewer) {
        return new CommentResponseDto(rootId,null,false,"root",false,null,1,Instant.EPOCH,3,liker.getId().equals(viewer));
    }
    private CommentReplyResponseDto reply(UUID viewer) {
        return new CommentReplyResponseDto(replyId,null,false,"reply",false,rootId,Instant.EPOCH,3,liker.getId().equals(viewer));
    }
    private UserDetailsImpl principal() {
        return new UserDetailsImpl(User.builder().id(UUID.randomUUID()).username("viewer").email("viewer@test.invalid")
                .password("unused").status(UserStatus.ACTIVE).emailVerified(true)
                .roles(Set.of(Role.builder().name("ROLE_LISTENER").build())).build());
    }
}
