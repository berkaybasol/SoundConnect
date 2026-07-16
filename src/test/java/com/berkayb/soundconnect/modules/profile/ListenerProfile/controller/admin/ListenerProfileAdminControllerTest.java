package com.berkayb.soundconnect.modules.profile.ListenerProfile.controller.admin;

import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.request.ListenerSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.response.ListenerProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.service.ListenerProfileService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.test.context.support.WithMockUser;

import java.util.UUID;

import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = ListenerProfileAdminController.class)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@Tag("web")
@WithMockUser(authorities = "MANAGE_PROFILES")
class ListenerProfileAdminControllerTest {
	
	@Autowired
	MockMvc mockMvc;
	
	private final ObjectMapper om = new ObjectMapper();
	
	@MockitoBean
	ListenerProfileService listenerProfileService;
	
	@MockitoBean
	com.berkayb.soundconnect.auth.security.JwtAuthenticationFilter jwtAuthenticationFilter;
	@MockitoBean
	com.berkayb.soundconnect.auth.security.JwtTokenProvider jwtTokenProvider;
	@MockitoBean
	com.berkayb.soundconnect.auth.service.CustomUserDetailsService customUserDetailsService;
	
	@Test
	void getListenerProfileByUserId_ok() throws Exception {
		UUID userId = UUID.randomUUID();
		UUID ppId = UUID.randomUUID();
		
		var dto = new ListenerProfileResponseDto(
				UUID.randomUUID(), userId, "listener", "bio", ppId,
				"https://cdn.example.com/profile.jpg", 0, 0);
		when(listenerProfileService.getProfileByUserId(userId)).thenReturn(dto);
		
		mockMvc.perform(get("/api/v1/admin/listener-profiles/by-user/{userId}", userId))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success", is(true)))
		       .andExpect(jsonPath("$.code", is(200)))
		       .andExpect(jsonPath("$.data.userId").value(userId.toString()))
		       .andExpect(jsonPath("$.data.profilePictureMediaId").value(ppId.toString()));
	}
	
	@Test
	void updateListenerProfileByUserId_ok() throws Exception {
		UUID userId = UUID.randomUUID();
		UUID newPpId = UUID.randomUUID();
		
		var body = new ListenerSaveRequestDto("upd", newPpId);
		var dto = new ListenerProfileResponseDto(
				UUID.randomUUID(), userId, "listener", "upd", newPpId,
				"https://cdn.example.com/profile.jpg", 0, 0);
		
		when(listenerProfileService.updateProfile(userId, body)).thenReturn(dto);
		
		mockMvc.perform(
			       put("/api/v1/admin/listener-profiles/by-user/{userId}/update", userId)
						       .contentType(MediaType.APPLICATION_JSON)
						       .content(om.writeValueAsString(body))
		       )
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success", is(true)))
		       .andExpect(jsonPath("$.code", is(200)))
		       .andExpect(jsonPath("$.data.bio").value("upd"))
		       .andExpect(jsonPath("$.data.profilePictureMediaId").value(newPpId.toString()));
	}
}
