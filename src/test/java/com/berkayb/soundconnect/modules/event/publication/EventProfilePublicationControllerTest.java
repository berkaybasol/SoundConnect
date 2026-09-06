package com.berkayb.soundconnect.modules.event.publication;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.event.enums.PerformerType;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.calendar.abuse.MusicianCalendarRateLimitGuard;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.shared.exception.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers=EventProfilePublicationController.class, excludeFilters=@ComponentScan.Filter(
        type=FilterType.ASSIGNABLE_TYPE, classes=com.berkayb.soundconnect.auth.security.JwtAuthenticationFilter.class))
@AutoConfigureMockMvc(addFilters=false)
@Import(EventProfilePublicationControllerTest.Security.class)
class EventProfilePublicationControllerTest {
    @TestConfiguration @EnableMethodSecurity static class Security { }
    @Autowired MockMvc mvc;
    @MockitoBean EventProfilePublicationService service;
    @MockitoBean MusicianCalendarRateLimitGuard guard;
    final UUID targetId=UUID.randomUUID(), eventId=UUID.randomUUID();
    static final String PATH="/api/v1/user/event-profile-publications";
    @AfterEach void clear(){SecurityContextHolder.clearContext();}

    @ParameterizedTest @ValueSource(strings={"ALL","CURRENT","FUTURE","PAST"})
    void periodIsForwardedWithoutClientSidePageFiltering(String period) throws Exception {
        UUID user=authenticate("ROLE_MUSICIAN");
        mvc.perform(get(PATH).param("targetType","MUSICIAN").param("targetId",targetId.toString())
                .param("period",period).param("page","2").param("size","10")).andExpect(status().isOk());
        verify(service).getMine(user,PerformerType.MUSICIAN,targetId,2,10,EventPublicationPeriod.valueOf(period));
    }

    @Test void unknownPeriodIsRejectedBeforeService() throws Exception {
        authenticate("ROLE_MUSICIAN");
        mvc.perform(get(PATH).param("targetType","MUSICIAN").param("targetId",targetId.toString())
                .param("period","UNKNOWN")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @ParameterizedTest @ValueSource(strings={"MUSICIAN","BAND"})
    void updatesCheckAuthorityThenScopedLimitThenMutation(String type) throws Exception {
        UUID user=authenticate("ROLE_MUSICIAN");
        var update=new EventProfilePublicationUpdate(PerformerType.valueOf(type),targetId,true,0L);
        mvc.perform(put(PATH+"/{eventId}",eventId).contentType(MediaType.APPLICATION_JSON).content(body(type)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control",containsString("no-store")));
        var order=inOrder(service,guard);
        order.verify(service).requireAuthority(user,update.targetType(),targetId);
        if(type.equals("BAND")) order.verify(guard).checkBand(targetId); else order.verify(guard).check(user);
        order.verify(service).update(user,eventId,update);
    }

    @Test void outsiderCannotSpendBandBudget() throws Exception {
        UUID user=authenticate("ROLE_MUSICIAN");
        doThrow(new SoundConnectException(ErrorType.FORBIDDEN_ACCESS)).when(service).requireAuthority(user,PerformerType.BAND,targetId);
        mvc.perform(put(PATH+"/{eventId}",eventId).contentType(MediaType.APPLICATION_JSON).content(body("BAND"))).andExpect(status().isForbidden());
        verifyNoInteractions(guard); verify(service,never()).update(any(),any(),any());
    }

    @Test void rateLimitReturnsRetryAfterBeforeMutation() throws Exception {
        UUID user=authenticate("ROLE_MUSICIAN");
        doThrow(new RateLimitedException(ErrorType.MUSICIAN_CALENDAR_RATE_LIMITED,10)).when(guard).check(user);
        mvc.perform(put(PATH+"/{eventId}",eventId).contentType(MediaType.APPLICATION_JSON).content(body("MUSICIAN")))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After","10"));
        verify(service,never()).update(any(),any(),any());
    }

    @Test void redisOutageFailsClosed() throws Exception {
        UUID user=authenticate("ROLE_MUSICIAN");
        doThrow(new ServiceUnavailableRetryException(ErrorType.MUSICIAN_CALENDAR_RATE_LIMIT_UNAVAILABLE,5)).when(guard).check(user);
        mvc.perform(put(PATH+"/{eventId}",eventId).contentType(MediaType.APPLICATION_JSON).content(body("MUSICIAN")))
                .andExpect(status().isServiceUnavailable()).andExpect(header().string("Retry-After","5"));
        verify(service,never()).update(any(),any(),any());
    }

    @ParameterizedTest @ValueSource(strings={"{}","{\"targetType\":\"MUSICIAN\"}","{\"targetType\":\"INVALID\"}"})
    void invalidBodyNeverReachesDatabase(String body) throws Exception {
        authenticate("ROLE_MUSICIAN");
        mvc.perform(put(PATH+"/{eventId}",eventId).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(service,guard);
    }

    @Test void unauthenticatedAndWrongRoleCannotReadOrWrite() throws Exception {
        mvc.perform(get(PATH).param("targetType","MUSICIAN").param("targetId",targetId.toString())).andExpect(status().isUnauthorized());
        authenticate("ROLE_VENUE");
        mvc.perform(get(PATH).param("targetType","MUSICIAN").param("targetId",targetId.toString())).andExpect(status().isForbidden());
        mvc.perform(put(PATH+"/{eventId}",eventId).contentType(MediaType.APPLICATION_JSON).content(body("MUSICIAN"))).andExpect(status().isForbidden());
        verifyNoInteractions(service,guard);
    }

    private String body(String type){return "{\"targetType\":\""+type+"\",\"targetId\":\""+targetId+"\",\"visible\":true,\"version\":0}";}
    private UUID authenticate(String role) {
        UUID id=UUID.randomUUID();
        User user=User.builder().id(id).username("publication_owner").password("unused").email("publication@example.com")
                .emailVerified(true).status(UserStatus.ACTIVE).roles(Set.of(Role.builder().name(role).build())).build();
        UserDetailsImpl principal=UserDetailsImpl.fromUser(user);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal,null,principal.getAuthorities()));
        return id;
    }
}
