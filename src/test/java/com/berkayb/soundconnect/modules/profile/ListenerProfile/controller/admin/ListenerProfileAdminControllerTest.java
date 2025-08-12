package com.berkayb.soundconnect.modules.profile.ListenerProfile.controller.admin;

import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.request.ListenerSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.response.ListenerProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.service.ListenerProfileService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = ListenerProfileAdminController.class)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
class ListenerProfileAdminControllerTest {
	
	@Autowired
	MockMvc mockMvc;
	
	private final ObjectMapper om = new ObjectMapper();
	
	// Controller’ın dependency’si
	@MockitoBean
	ListenerProfileService listenerProfileService;
	
	// Security tarafını susturuyoruz
	@MockitoBean
	com.berkayb.soundconnect.auth.security.JwtAuthenticationFilter jwtAuthenticationFilter;
	@MockitoBean
	com.berkayb.soundconnect.auth.security.JwtTokenProvider jwtTokenProvider;
	@MockitoBean
	com.berkayb.soundconnect.auth.service.CustomUserDetailsService customUserDetailsService;
	
	@Test
	void getListenerProfileByUserId_ok() throws Exception {
		UUID userId = UUID.randomUUID();
		var dto = new ListenerProfileResponseDto(UUID.randomUUID(), "bio", "pp.png", userId);
		
		when(listenerProfileService.getProfileByUserId(userId)).thenReturn(dto);
		
		mockMvc.perform(get("/api/v1/admin/listener-profiles/by-user/{userId}", userId))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success", is(true)))
		       .andExpect(jsonPath("$.code", is(200)))
		       .andExpect(jsonPath("$.data.userId").value(userId.toString()));
	}
	
	@Test
	void updateListenerProfileByUserId_ok() throws Exception {
		UUID userId = UUID.randomUUID();
		var body = new ListenerSaveRequestDto("upd", "pic.png");
		var dto = new ListenerProfileResponseDto(UUID.randomUUID(), "upd", "pic.png", userId);
		
		when(listenerProfileService.updateProfile(userId, body)).thenReturn(dto);
		
		mockMvc.perform(
				       put("/api/v1/admin/listener-profiles/update/{userId}", userId)
						       .contentType(MediaType.APPLICATION_JSON)
						       .content(om.writeValueAsString(body))
		       )
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success", is(true)))
		       .andExpect(jsonPath("$.code", is(200)))
		       .andExpect(jsonPath("$.data.bio").value("upd"));
	}
}