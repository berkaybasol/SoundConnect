package com.berkayb.soundconnect.modules.profile.StudioProfille.controller.admin;

import com.berkayb.soundconnect.auth.security.JwtTokenProvider;
import com.berkayb.soundconnect.auth.service.CustomUserDetailsService;
import com.berkayb.soundconnect.modules.profile.StudioProfile.controller.admin.StudioProfileAdminController;
import com.berkayb.soundconnect.modules.profile.StudioProfile.dto.request.StudioProfileSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.StudioProfile.dto.response.StudioProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.StudioProfile.service.StudioProfileService;
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

import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = StudioProfileAdminController.class)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@Tag("web")
class StudioProfileAdminControllerTest {
	
	@MockitoBean StudioProfileService studioProfileService;
	
	// security beanlerini susturalım
	@MockitoBean JwtTokenProvider jwtTokenProvider;
	@MockitoBean CustomUserDetailsService customUserDetailsService;
	@MockitoBean com.berkayb.soundconnect.shared.util.JwtUtil jwtUtil;
	
	@Autowired MockMvc mockMvc;
	private final ObjectMapper om = new ObjectMapper();
	
	@Test
	void getStudioProfileByUserId_ok() throws Exception {
		UUID userId = UUID.randomUUID();
		var dto = new StudioProfileResponseDto(
				UUID.randomUUID(),
				"S1",
				"bio",
				"pp.png",
				"addr",
				"555",
				"site",
				Set.of("mixing"),
				"insta",
				"yt"
		);
		
		when(studioProfileService.getProfileByUserId(userId)).thenReturn(dto);
		
		mockMvc.perform(get("/api/v1/admin/studio-profiles/by-user/{userId}", userId))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success", is(true)))
		       .andExpect(jsonPath("$.code", is(200)))
		       .andExpect(jsonPath("$.data.name").value("S1"))
		       .andExpect(jsonPath("$.data.facilities[0]").value("mixing"));
	}
	
	@Test
	void updateStudioProfileByUserId_ok() throws Exception {
		UUID userId = UUID.randomUUID();
		
		var req = new StudioProfileSaveRequestDto(
				"New", "d", "new.png", "new addr", "555",
				"site.com", Set.of("parking"), "ig", "yt"
		);
		
		var resp = new StudioProfileResponseDto(
				UUID.randomUUID(), "New", "d", "new.png",
				"new addr", "555", "site.com", Set.of("parking"), "ig", "yt"
		);
		
		when(studioProfileService.updateProfile(userId, req)).thenReturn(resp);
		
		mockMvc.perform(put("/api/v1/admin/studio-profiles/by-user/{userId}/update", userId)
				                .contentType(MediaType.APPLICATION_JSON)
				                .content(om.writeValueAsString(req)))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success", is(true)))
		       .andExpect(jsonPath("$.code", is(200)))
		       .andExpect(jsonPath("$.data.name").value("New"))
		       .andExpect(jsonPath("$.data.facilities[0]").value("parking"));
	}
}