package com.berkayb.soundconnect.modules.tablegroup.profileshare;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
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
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = TableGroupProfileShareController.class,
        properties = {"spring.config.location=classpath:/application-test.yml", "spring.config.import="})
@ContextConfiguration(classes = {TableGroupProfileShareController.class, GlobalExceptionHandler.class,
        TableGroupProfileShareControllerTest.Security.class})
@AutoConfigureMockMvc(addFilters = false)
class TableGroupProfileShareControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean TableGroupProfileShareService shares;

    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @Test void allRoutesRequireAuthentication() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(get(sourcePath(id))).andExpect(status().isUnauthorized());
        mvc.perform(put(sourcePath(id)).contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/v1/table-groups/profile-shares/" + id)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/public/listener-profiles/" + id + "/table-group-posts"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/public/listener-profiles/" + id + "/table-group-posts/lookup").param("shareIds",id.toString()))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(shares);
    }

    @Test void strictlyAcceptsOnlyOptionalTextNoteNeverClientMembershipOrOwner() throws Exception {
        authenticate(UUID.randomUUID());
        for (String body : List.of("[]", "{\"note\":1}", "{\"note\":true}", "{\"note\":[]}",
                "{\"note\":{}}", "{\"ownerId\":\"forged\"}", "{\"publishedOnProfile\":true}",
                "{\"note\":\"first\",\"note\":\"second\"}")) {
            mvc.perform(put(sourcePath(UUID.randomUUID())).contentType(APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(shares);
    }

    @Test void stateUsesAuthenticatedAccountAndIsNeverPubliclyCacheable() throws Exception {
        UUID owner=UUID.randomUUID(), source=UUID.randomUUID(), share=UUID.randomUUID();
        authenticate(owner);
        when(shares.get(owner,source)).thenReturn(new TableGroupProfileShareResponse.State(
                source,share,true,"Not",Instant.parse("2026-09-10T10:00:00Z"),false,null));
        mvc.perform(get(sourcePath(source)).param("ownerId",UUID.randomUUID().toString()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","private, no-store"))
                .andExpect(jsonPath("$.data.shareId").value(share.toString()))
                .andExpect(jsonPath("$.data.canPublish").value(false));
        verify(shares).get(owner,source);
    }

    @Test void optionalNullNotesAndExactDeleteKeepEstablishedContract() throws Exception {
        UUID owner=UUID.randomUUID(), source=UUID.randomUUID(), share=UUID.randomUUID();
        authenticate(owner);
        for (String body : List.of("{}", "{\"note\":null}")) {
            mvc.perform(put(sourcePath(source)).contentType(APPLICATION_JSON).content(body)).andExpect(status().isOk());
        }
        verify(shares,times(2)).publish(owner,source,new TableGroupProfileShareUpdate(null));
        mvc.perform(delete("/api/v1/table-groups/profile-shares/"+share)).andExpect(status().isOk());
        verify(shares).delete(owner,share);
    }

    @Test void semanticRejectionsAndUnavailableSchemaAreExplicit() throws Exception {
        UUID owner=UUID.randomUUID(), source=UUID.randomUUID(); authenticate(owner);
        when(shares.publish(owner,source,new TableGroupProfileShareUpdate("note")))
                .thenThrow(new SoundConnectException(ErrorType.TABLE_GROUP_PROFILE_SHARE_ALREADY_EXISTS));
        mvc.perform(put(sourcePath(source)).contentType(APPLICATION_JSON).content("{\"note\":\"note\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(9129));
        when(shares.get(owner,source)).thenThrow(new DataAccessResourceFailureException("missing migration"));
        mvc.perform(get(sourcePath(source))).andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After","5")).andExpect(jsonPath("$.code").value(9132));
    }

    @Test void visibleLookupBindsOnlyAuthenticatedViewerAndReturnsExistingEnvelope() throws Exception {
        UUID viewer=UUID.randomUUID(), profile=UUID.randomUUID(), first=UUID.randomUUID(), second=UUID.randomUUID();
        authenticate(viewer);
        when(shares.lookup(viewer,profile,List.of(first,second))).thenReturn(List.of());
        mvc.perform(get("/api/v1/public/listener-profiles/"+profile+"/table-group-posts/lookup")
                        .param("shareIds",first+","+second).param("viewerId",UUID.randomUUID().toString()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","private, no-store"))
                .andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
        verify(shares).lookup(viewer,profile,List.of(first,second));
        mvc.perform(get("/api/v1/public/listener-profiles/"+profile+"/table-group-posts/lookup").param("shareIds","invalid"))
                .andExpect(status().isBadRequest());
        verifyNoMoreInteractions(shares);
    }

    private String sourcePath(UUID id) { return "/api/v1/table-groups/"+id+"/profile-share"; }
    private void authenticate(UUID id) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                UserDetailsImpl.fromUser(User.builder().id(id).username("test-user").build()),null,List.of()));
    }
    @TestConfiguration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class Security { }
}
