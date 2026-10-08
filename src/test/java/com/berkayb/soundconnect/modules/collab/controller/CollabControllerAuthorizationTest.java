package com.berkayb.soundconnect.modules.collab.controller;

import com.berkayb.soundconnect.modules.collab.service.CollabService;
import com.berkayb.soundconnect.auth.security.JwtAuthenticationFilter;
import com.berkayb.soundconnect.auth.security.JwtTokenProvider;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = CollabController.class)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@Import(CollabControllerAuthorizationTest.MethodSecurityConfig.class)
class CollabControllerAuthorizationTest {
    @Autowired MockMvc mockMvc;
    @MockitoBean CollabService service;
    @MockitoBean JwtTokenProvider jwtTokenProvider;
    @MockitoBean JwtAuthenticationFilter jwtAuthenticationFilter;

    private static final String ID = "00000000-0000-0000-0000-000000000001";
    private static final String VERSION = "{\"expectedVersion\":0}";
    private static final String DRAFT = """
            {"clientRequestId":"%s","publisherActorId":"%s","expectedVersion":0,
             "cadence":"REGULAR","wantedType":"MUSICIAN","title":"Müzisyen aranıyor",
             "description":"Projemiz için deneyimli bir müzisyen arıyoruz.","cityId":"%s"}
            """.formatted(ID, ID, ID);
    private record Endpoint(String verb, String path, String body) { }
    static Stream<Arguments> deniedEndpoints() {
        return Stream.of(
                new Endpoint("GET", "/actors/me", null), new Endpoint("GET", "", null),
                new Endpoint("GET", "/" + ID, null), new Endpoint("POST", "/drafts", DRAFT),
                new Endpoint("PUT", "/" + ID, DRAFT), new Endpoint("POST", "/" + ID + "/publish", VERSION),
                new Endpoint("DELETE", "/drafts/" + ID, VERSION), new Endpoint("POST", "/" + ID + "/close", VERSION),
                new Endpoint("GET", "/me/listings", null), new Endpoint("PUT", "/" + ID + "/saved", null),
                new Endpoint("DELETE", "/" + ID + "/saved", null), new Endpoint("GET", "/me/saved", null),
                new Endpoint("POST", "/" + ID + "/applications",
                        "{\"clientRequestId\":\"" + ID + "\",\"applicantActorId\":\"" + ID + "\",\"phoneNumber\":\"05321234567\"}"),
                new Endpoint("GET", "/me/listings/" + ID + "/applications", null),
                new Endpoint("GET", "/me/applications", null),
                new Endpoint("POST", "/applications/" + ID + "/accept", VERSION),
                new Endpoint("POST", "/applications/" + ID + "/reject", VERSION),
                new Endpoint("POST", "/applications/" + ID + "/withdraw", VERSION),
                new Endpoint("GET", "/me/jobs", null),
                new Endpoint("POST", "/jobs/" + ID + "/confirm-completion", VERSION),
                new Endpoint("POST", "/jobs/" + ID + "/reviews", "{\"clientRequestId\":\"" + ID + "\",\"rating\":4}"),
                new Endpoint("GET", "/actors/" + ID + "/reviews", null),
                new Endpoint("POST", "/" + ID + "/reports", "{\"clientRequestId\":\"" + ID + "\",\"reason\":\"SPAM\"}")
        ).flatMap(endpoint -> Stream.of("", "MUSICIAN", "VENUE", "STUDIO")
                .map(extra -> Arguments.of(endpoint, extra)));
    }

    @ParameterizedTest(name = "{0} listener + {1}") @MethodSource("deniedEndpoints")
    void everyEndpointRejectsListenerEvenWithAnAdditionalBusinessAuthority(Endpoint endpoint, String extraRole) throws Exception {
        authenticate(extraRole.isEmpty() ? List.of("ROLE_LISTENER") : List.of("ROLE_LISTENER", "ROLE_" + extraRole));
        try {
            var request = request(HttpMethod.valueOf(endpoint.verb()), "/api/v1/collabs" + endpoint.path());
            if (endpoint.body() != null) request.contentType("application/json").content(endpoint.body());
            mockMvc.perform(request).andExpect(status().isForbidden());
            verifyNoInteractions(service);
        } finally { SecurityContextHolder.clearContext(); }
    }

    @ParameterizedTest @ValueSource(strings = {"MUSICIAN", "VENUE", "STUDIO"})
    void businessRolesKeepTheirExistingEndpointAndResponsesCannotBeSharedCached(String role) throws Exception {
        UUID userId = authenticate(List.of("ROLE_" + role));
        when(service.actorsMine(userId)).thenReturn(List.of());
        try {
            mockMvc.perform(get("/api/v1/collabs/actors/me")).andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-store, private"));
            verify(service).actorsMine(userId);
        } finally { SecurityContextHolder.clearContext(); }
    }

    private UUID authenticate(List<String> authorities) {
        UUID id = UUID.randomUUID();
        UserDetailsImpl principal = mock(UserDetailsImpl.class);
        when(principal.getId()).thenReturn(id);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null,
                authorities.stream().map(SimpleGrantedAuthority::new).toList()));
        return id;
    }

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityConfig {}
}
