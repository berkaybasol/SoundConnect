package com.berkayb.soundconnect.modules.marketplace;

import com.berkayb.soundconnect.auth.security.*;
import com.berkayb.soundconnect.modules.marketplace.controller.*;
import com.berkayb.soundconnect.modules.marketplace.service.MarketplaceService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import java.util.stream.Stream;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers={MarketplaceController.class,MarketplaceAdminController.class})
@AutoConfigureMockMvc(addFilters=false)
@ActiveProfiles("test")
@Import(MarketplaceControllerAuthorizationTest.MethodSecurity.class)
class MarketplaceControllerAuthorizationTest {
    @Autowired MockMvc mvc;
    @MockitoBean MarketplaceService service;
    @MockitoBean JwtTokenProvider jwtTokenProvider;
    @MockitoBean JwtAuthenticationFilter jwtAuthenticationFilter;
    static final String ID="00000000-0000-0000-0000-000000000001";
    static final String BASE="/api/v1/user/marketplace";
    record Endpoint(String verb,String path,String body) {}
    static Stream<Arguments> endpoints() {
        return Stream.of(
                new Endpoint("GET","/categories",null),new Endpoint("GET","/listings",null),
                new Endpoint("GET","/listings/"+ID,null),new Endpoint("GET","/my-listings",null),
                new Endpoint("GET","/saved",null),new Endpoint("POST","/drafts","{\"clientRequestId\":\""+ID+"\"}"),
                new Endpoint("PUT","/listings/"+ID,"{\"expectedVersion\":0,\"photoIds\":[],\"negotiable\":false}"),
                new Endpoint("POST","/listings/"+ID+"/publish","{\"expectedVersion\":0}"),
                new Endpoint("POST","/listings/"+ID+"/sold","{\"expectedVersion\":0}"),
                new Endpoint("POST","/listings/"+ID+"/withdraw","{\"expectedVersion\":0}"),
                new Endpoint("DELETE","/listings/"+ID+"?expectedVersion=0",null),
                new Endpoint("PUT","/listings/"+ID+"/saved",null),new Endpoint("DELETE","/listings/"+ID+"/saved",null),
                new Endpoint("POST","/listings/"+ID+"/reports","{\"reason\":\"SPAM\",\"clientRequestId\":\""+ID+"\"}")
        ).flatMap(endpoint->Stream.of("","MUSICIAN","STUDIO","VENUE").map(extra->Arguments.of(endpoint,extra)));
    }
    @ParameterizedTest @MethodSource("endpoints")
    void listenerNeverReachesAnyEndpointEvenWithAdditionalBackstageAuthority(Endpoint endpoint,String extra)throws Exception {
        authenticate(extra.isEmpty()?List.of("ROLE_LISTENER"):List.of("ROLE_LISTENER","ROLE_"+extra));
        try {
            var request=request(HttpMethod.valueOf(endpoint.verb()),BASE+endpoint.path());
            if(endpoint.body()!=null) request.contentType("application/json").content(endpoint.body());
            mvc.perform(request).andExpect(status().isForbidden());verifyNoInteractions(service);
        } finally {SecurityContextHolder.clearContext();}
    }
    @ParameterizedTest @ValueSource(strings={"MUSICIAN","STUDIO","VENUE"})
    void allowedRolesReceivePrivateNoStoreResponses(String role)throws Exception {
        UUID user=authenticate(List.of("ROLE_"+role));when(service.categories(user)).thenReturn(List.of());
        try {mvc.perform(get(BASE+"/categories")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store, private"));}
        finally {SecurityContextHolder.clearContext();}
    }
    @ParameterizedTest @ValueSource(strings={"ROLE_MUSICIAN","ROLE_LISTENER","ROLE_ADMIN"})
    void moderatorEndpointsRequireExplicitPermission(String authority)throws Exception {
        authenticate(List.of(authority));
        try {mvc.perform(get("/api/v1/admin/marketplace/reports")).andExpect(status().isForbidden());verifyNoInteractions(service);}
        finally {SecurityContextHolder.clearContext();}
    }
    private UUID authenticate(List<String> roles) {
        UserDetailsImpl principal=mock(UserDetailsImpl.class);UUID id=UUID.randomUUID();when(principal.getId()).thenReturn(id);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal,null,
                roles.stream().map(SimpleGrantedAuthority::new).toList()));return id;
    }
    @TestConfiguration @EnableMethodSecurity static class MethodSecurity {}
}
