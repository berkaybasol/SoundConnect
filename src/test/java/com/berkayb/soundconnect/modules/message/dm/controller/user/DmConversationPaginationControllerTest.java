package com.berkayb.soundconnect.modules.message.dm.controller.user;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.message.dm.dto.response.DMConversationPageResponseDto;
import com.berkayb.soundconnect.modules.message.dm.dto.response.DMConversationPreviewResponseDto;
import com.berkayb.soundconnect.modules.message.dm.service.DMConversationService;
import com.berkayb.soundconnect.modules.message.dm.service.DmConversationQueryService;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DMConversationUserController.class)
@AutoConfigureMockMvc(addFilters=false)
class DmConversationPaginationControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean DMConversationService legacy;
    @MockitoBean DmConversationQueryService queries;
    @MockitoBean com.berkayb.soundconnect.auth.security.JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockitoBean com.berkayb.soundconnect.auth.security.JwtTokenProvider jwtTokenProvider;
    UUID actor;
    @BeforeEach void authenticate() {
        actor=UUID.randomUUID();
        var principal=new UserDetailsImpl(User.builder().id(actor).username("fixture").build());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal,null,List.of()));
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void pageEnvelopeAndCursorAreForwardedWithAuthenticatedOwner() throws Exception {
        var preview=new DMConversationPreviewResponseDto(UUID.randomUUID(),UUID.randomUUID(),"peer",null,null,null,null,null,null);
        when(queries.page(actor,2,"opaque")).thenReturn(new DMConversationPageResponseDto(List.of(preview),true,"next"));
        mvc.perform(get("/api/v1/user/dm/conversations/my/page").param("size","2").param("cursor","opaque"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content[0].conversationId").value(preview.conversationId().toString()))
                .andExpect(jsonPath("$.data.hasNext").value(true)).andExpect(jsonPath("$.data.nextCursor").value("next"));
        verify(queries).page(actor,2,"opaque"); verifyNoInteractions(legacy);
    }

    @Test void defaultPageAndInvalidSizeUseEstablishedHttpContract() throws Exception {
        when(queries.page(actor,30,null)).thenReturn(new DMConversationPageResponseDto(List.of(),false,null));
        mvc.perform(get("/api/v1/user/dm/conversations/my/page"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content").isEmpty())
                .andExpect(jsonPath("$.data.hasNext").value(false));
        when(queries.page(actor,101,null)).thenThrow(new SoundConnectException(ErrorType.BAD_REQUEST));
        mvc.perform(get("/api/v1/user/dm/conversations/my/page").param("size","101"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(ErrorType.BAD_REQUEST.getCode()));
    }

    @Test void directPreviewDoesNotEnumerateOtherConversationsAndForeignTargetIs404() throws Exception {
        UUID conversation=UUID.randomUUID();
        var preview=new DMConversationPreviewResponseDto(conversation,UUID.randomUUID(),"peer",null,null,null,null,null,null);
        when(queries.preview(actor,conversation)).thenReturn(preview);
        mvc.perform(get("/api/v1/user/dm/conversations/{id}/preview",conversation))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.conversationId").value(conversation.toString()));
        when(queries.preview(actor,conversation)).thenThrow(new SoundConnectException(ErrorType.CONVERSATION_NOT_FOUND));
        mvc.perform(get("/api/v1/user/dm/conversations/{id}/preview",conversation)).andExpect(status().isNotFound());
        verifyNoInteractions(legacy);
    }
}
