package com.berkayb.soundconnect.modules.notification.campaign;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(properties="spring.config.import=")
@ContextConfiguration(classes={CampaignController.class,CustomNotificationController.class,CampaignApiSecurityTest.Security.class,
    com.berkayb.soundconnect.shared.exception.GlobalExceptionHandler.class})
class CampaignApiSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean CampaignService campaigns;
    @MockitoBean CampaignAccess access;
    @MockitoBean CampaignTargets targets;
    @Configuration @EnableWebSecurity @EnableMethodSecurity
    static class Security {
        @Bean SecurityFilterChain chain(HttpSecurity http)throws Exception{return http.csrf(csrf->csrf.disable())
            .authorizeHttpRequests(a->a.anyRequest().authenticated()).exceptionHandling(e->e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))).build();}
    }
    @Test void anonymousRegularAndMixedListenerAdminCannotCreateOrEnumerateRecipients()throws Exception{
        String base="/api/v1/admin/notifications/campaigns";
        mvc.perform(get(base)).andExpect(status().isUnauthorized());
        for(String suffix:List.of("","/users?q=ab","/targets?kind=PROFILE&q=ab")){
            mvc.perform(get(base+suffix).with(user("musician").roles("MUSICIAN"))).andExpect(status().isForbidden());
            mvc.perform(get(base+suffix).with(user("mixed").roles("ADMIN","LISTENER"))).andExpect(status().isForbidden());
        }
        verifyNoInteractions(campaigns,access,targets);
    }
    @Test void ownerCommandsAndCustomTargetAlwaysUseAuthenticatedIdentity()throws Exception{
        UUID owner=UUID.randomUUID(),id=UUID.randomUUID();var principal=mock(UserDetailsImpl.class);when(principal.getId()).thenReturn(owner);
        var auth=new UsernamePasswordAuthenticationToken(principal,"unused",List.of(new SimpleGrantedAuthority("ROLE_OWNER")));
        mvc.perform(post("/api/v1/admin/notifications/campaigns/"+id+"/schedule").with(authentication(auth))
            .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":2,\"userId\":\""+UUID.randomUUID()+"\"}")).andExpect(status().isOk());
        verify(campaigns).action(owner,id,"schedule",new CampaignContract.Version(2L));
        mvc.perform(get("/api/v1/user/notifications/"+id+"/custom-target?userId="+UUID.randomUUID()).with(authentication(auth))).andExpect(status().isOk());
        verify(targets).resolve(owner,id);
    }

    @ParameterizedTest(name = "non-admin {0} cannot use any campaign administration route")
    @ValueSource(strings = {"MUSICIAN", "LISTENER", "VENUE", "STUDIO"})
    void everyPersonalProfileIsForbiddenFromAllAdministrationReadsAndWrites(String role)throws Exception{
        String base="/api/v1/admin/notifications/campaigns";
        UUID id=UUID.randomUUID();
        for(String suffix:List.of("","/"+id,"/users?q=ab","/targets?kind=PROFILE&q=ab"))
            mvc.perform(get(base+suffix).with(user("profile").roles(role))).andExpect(status().isForbidden());
        mvc.perform(post(base).with(user("profile").roles(role)).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden());
        mvc.perform(put(base+"/"+id).with(user("profile").roles(role)).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden());
        for(String action:List.of("schedule","pause","resume","cancel"))
            mvc.perform(post(base+"/"+id+"/"+action).with(user("profile").roles(role))
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(campaigns,access,targets);
    }
}
