package com.berkayb.soundconnect.modules.event.audience;

import com.berkayb.soundconnect.auth.ratelimit.AuthRateLimitFilter;
import com.berkayb.soundconnect.auth.security.*;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.shared.config.SecurityConfig;
import com.berkayb.soundconnect.shared.exception.*;
import com.berkayb.soundconnect.shared.security.*;
import com.berkayb.soundconnect.shared.response.PageResponse;
import jakarta.servlet.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import java.util.*;
import java.time.Instant;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(EventAudienceController.class)
@Import({SecurityConfig.class,RestAuthenticationEntryPoint.class,RestAccessDeniedHandler.class,SecurityErrorResponseWriter.class})
class EventAudienceHttpTest {
    static final UUID USER=UUID.randomUUID(),EVENT=UUID.randomUUID(),PROFILE=UUID.randomUUID();
    static final String MINE="/api/v1/user/event-intents",ONE=MINE+"/"+EVENT,POSTS="/api/v1/public/listener-profiles/"+PROFILE+"/event-posts";
    @Autowired MockMvc mvc;
    @MockitoBean EventAudienceService service; @MockitoBean EventAudienceRateGuard guard;
    @MockitoBean JwtAuthenticationFilter jwt; @MockitoBean AuthRateLimitFilter authRateLimit;
    @BeforeEach void filters() throws Exception {
        doAnswer(call -> { ((FilterChain)call.getArgument(2)).doFilter(call.getArgument(0),call.getArgument(1)); return null; })
                .when(jwt).doFilter(any(ServletRequest.class),any(ServletResponse.class),any(FilterChain.class));
        doAnswer(call -> { ((FilterChain)call.getArgument(2)).doFilter(call.getArgument(0),call.getArgument(1)); return null; })
                .when(authRateLimit).doFilter(any(ServletRequest.class),any(ServletResponse.class),any(FilterChain.class));
    }
    @Test void privateStateAndAuthenticatedSocialPostsAreNeverGuestReadable() throws Exception {
        for(String path:List.of(MINE,ONE,POSTS)) mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(put(ONE).contentType("application/json").content(body())).andExpect(status().isUnauthorized());
        verifyNoInteractions(service,guard);
    }
    @ParameterizedTest @ValueSource(strings={"ROLE_LISTENER","ROLE_MUSICIAN"})
    void personalRolesUseExactPrincipalAndFullStateMutation(String role) throws Exception {
        mvc.perform(put(ONE).with(viewer(role)).param("userId",UUID.randomUUID().toString()).contentType("application/json").content(body()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","private, no-store"));
        var order=inOrder(service,guard); order.verify(service).requireAuthority(USER); order.verify(guard).check(USER);
        order.verify(service).update(USER,EVENT,new EventIntentUpdate(EventIntent.GOING,false,null,0L));
    }
    @ParameterizedTest @ValueSource(strings={"ROLE_VENUE","ROLE_STUDIO","ROLE_ADMIN","ROLE_USER","ROLE_ORGANIZER","ROLE_PRODUCER"})
    void businessAndStaffRolesCannotSetOrReadPrivateAudienceState(String role) throws Exception {
        mvc.perform(get(ONE).with(viewer(role))).andExpect(status().isForbidden());
        mvc.perform(get(MINE).with(viewer(role))).andExpect(status().isForbidden());
        mvc.perform(put(ONE).with(viewer(role)).contentType("application/json").content(body())).andExpect(status().isForbidden());
        verifyNoInteractions(service,guard);
    }
    @Test void publicProfilePostsKeepExistingAuthenticatedViewerPolicyAndStrictPageDefaults() throws Exception {
        mvc.perform(get(POSTS).with(viewer("ROLE_VENUE"))).andExpect(status().isOk()).andExpect(header().string("Cache-Control","private, no-store"));
        verify(service).posts(USER,PROFILE,EventIntentPeriod.ALL,0,20);
        mvc.perform(get(MINE).with(viewer("ROLE_MUSICIAN"))).andExpect(status().isOk());
        verify(service).mine(USER,EventIntentPeriod.UPCOMING,0,20);
    }
    @ParameterizedTest @ValueSource(strings={
            "{}","null","[]", "{\"intent\":\"GOING\",\"publishedOnProfile\":false,\"expectedVersion\":0}",
            "{\"intent\":\"GOING\",\"publishedOnProfile\":false,\"note\":null,\"expectedVersion\":\"0\"}",
            "{\"intent\":\"GOING\",\"publishedOnProfile\":0,\"note\":null,\"expectedVersion\":0}",
            "{\"intent\":\"going\",\"publishedOnProfile\":false,\"note\":null,\"expectedVersion\":0}",
            "{\"intent\":\"GOING\",\"publishedOnProfile\":false,\"note\":12,\"expectedVersion\":0}",
            "{\"intent\":\"GOING\",\"publishedOnProfile\":false,\"note\":null,\"expectedVersion\":0.0}",
            "{\"intent\":\"GOING\",\"publishedOnProfile\":false,\"note\":null,\"expectedVersion\":0,\"userId\":\"spoof\"}",
            "{\"intent\":\"GOING\",\"intent\":\"THINKING\",\"publishedOnProfile\":false,\"note\":null,\"expectedVersion\":0}"})
    void malformedOrForgedCommandsFailBeforeAuthorityQuotaOrStorage(String body) throws Exception {
        mvc.perform(put(ONE).with(viewer("ROLE_LISTENER")).contentType("application/json").content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(service,guard);
    }
    @Test void staleAndUnavailableResponsesDoNotLeakStateAndHavePrivateCacheHeaders() throws Exception {
        when(service.update(any(),any(),any())).thenThrow(new SoundConnectException(ErrorType.EVENT_INTENT_VERSION_CONFLICT));
        mvc.perform(put(ONE).with(viewer("ROLE_LISTENER")).contentType("application/json").content(body()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(9921)).andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(header().string("Cache-Control","private, no-store"));
        when(service.get(USER,EVENT)).thenThrow(new DataAccessResourceFailureException("private database info"));
        mvc.perform(get(ONE).with(viewer("ROLE_LISTENER"))).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(9924)).andExpect(header().string("Retry-After","5"))
                .andExpect(header().string("Cache-Control","private, no-store"));
    }
    @Test void invalidPeriodAndWrongMutationMethodsDoNotReachService() throws Exception {
        mvc.perform(get(MINE).with(viewer("ROLE_LISTENER")).param("period","CURRENT")).andExpect(status().isBadRequest());
        mvc.perform(delete(ONE).with(viewer("ROLE_LISTENER"))).andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(service,guard);
    }
    @Test void privateStateAndPublicPostHaveDistinctStableWireContracts() throws Exception {
        Instant instant=Instant.parse("2026-09-08T12:00:00Z");
        var own=new EventIntentResponse.State(EVENT,EventIntent.THINKING,true,"Owner note",4,instant,false,false,false,false,false,null);
        when(service.get(USER,EVENT)).thenReturn(own);
        mvc.perform(get(ONE).with(viewer("ROLE_LISTENER"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.eventId").value(EVENT.toString())).andExpect(jsonPath("$.data.intent").value("THINKING"))
                .andExpect(jsonPath("$.data.version").value(4)).andExpect(jsonPath("$.data.eventAvailable").value(false))
                .andExpect(jsonPath("$.data.publicationVisible").value(false)).andExpect(jsonPath("$.data.canPublish").value(false));
        when(service.posts(USER,PROFILE,EventIntentPeriod.ALL,0,20)).thenReturn(new PageResponse<>(
                List.of(new EventIntentResponse.Post(EVENT,EventIntent.GOING,"Public note",instant,true,null)),0,20,1,1,true,true));
        mvc.perform(get(POSTS).with(viewer("ROLE_LISTENER"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(0)).andExpect(jsonPath("$.data.number").value(0))
                .andExpect(jsonPath("$.data.totalElements").value(1)).andExpect(jsonPath("$.data.content[0].eventEnded").value(true))
                .andExpect(jsonPath("$.data.content[0].intent").value("GOING"))
                .andExpect(jsonPath("$.data.content[0].version").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].publishedOnProfile").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].canPublish").doesNotExist());
    }
    @Test void currentAccountFailureAndQuotaFailurePreventMutation() throws Exception {
        doThrow(new SoundConnectException(ErrorType.FORBIDDEN_ACCESS)).when(service).requireAuthority(USER);
        mvc.perform(put(ONE).with(viewer("ROLE_LISTENER")).contentType("application/json").content(body())).andExpect(status().isForbidden());
        verifyNoInteractions(guard); verify(service,never()).update(any(),any(),any());
        doNothing().when(service).requireAuthority(USER);
        doThrow(new RateLimitedException(ErrorType.EVENT_INTENT_RATE_LIMITED,17)).when(guard).check(USER);
        mvc.perform(put(ONE).with(viewer("ROLE_LISTENER")).contentType("application/json").content(body()))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After","17"))
                .andExpect(jsonPath("$.code").value(9923)).andExpect(header().string("Cache-Control","private, no-store"));
        verify(service,never()).update(any(),any(),any());
    }
    static String body() { return "{\"intent\":\"GOING\",\"publishedOnProfile\":false,\"note\":null,\"expectedVersion\":0}"; }
    static RequestPostProcessor viewer(String role) {
        User user=User.builder().username("audience-test").build(); user.setId(USER);
        return authentication(UsernamePasswordAuthenticationToken.authenticated(new UserDetailsImpl(user),null,List.of(new SimpleGrantedAuthority(role))));
    }
}
