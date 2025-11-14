package com.berkayb.soundconnect.modules.profile.MusicianProfile.controller.admin;

import com.berkayb.soundconnect.modules.profile.MusicianProfile.dto.request.MusicianProfileSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.dto.response.MusicianProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.service.MusicianProfileService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Set;
import java.util.UUID;

import static com.berkayb.soundconnect.shared.constant.EndPoints.MusicianProfile.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = MusicianProfileAdminController.class)
@AutoConfigureMockMvc(addFilters = false)
@Tag("web")
class MusicianProfileAdminControllerIT {
	
	@Autowired MockMvc mockMvc;
	@Autowired ObjectMapper om;
	
	@MockitoBean
	private MusicianProfileService service;
	
	@MockitoBean
	private com.berkayb.soundconnect.auth.security.JwtTokenProvider jwtTokenProvider;
	
	@MockitoBean
	private com.berkayb.soundconnect.auth.security.JwtAuthenticationFilter jwtAuthenticationFilter;
	
	@Test
	void getMusicianProfileByUserId_ok() throws Exception {
		var uid = UUID.randomUUID();
		
		var resp = new MusicianProfileResponseDto(
				UUID.randomUUID(),
				"Stage",
				"Bio",
				null,null,null,null,null,
				Set.of(),         // instruments
				Set.of(),         // activeVenues
				Set.of()          // bands <-- EKLENDİ
		);
		
		Mockito.when(service.getProfileByUserId(uid)).thenReturn(resp);
		
		mockMvc.perform(get(ADMIN_BASE + BY_USER_ID, uid))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.data.stageName").value("Stage"))
		       .andExpect(jsonPath("$.data.bands").isArray()); // <-- yeni assertion
	}
	
	@Test
	void updateMusicianProfileByUserId_ok() throws Exception {
		var uid = UUID.randomUUID();
		var req = new MusicianProfileSaveRequestDto(
				"New","NewBio",null,null,null,null,null,null
		);
		
		var resp = new MusicianProfileResponseDto(
				UUID.randomUUID(),
				"New",
				"NewBio",
				null,null,null,null,null,
				Set.of(),
				Set.of(),
				Set.of() // <-- EKLENDİ
		);
		
		Mockito.when(service.updateProfile(eq(uid), any())).thenReturn(resp);
		
		mockMvc.perform(
				       put(ADMIN_BASE + ADMIN_UPDATE, uid)
						       .contentType(MediaType.APPLICATION_JSON)
						       .content(om.writeValueAsString(req))
		       )
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.data.stageName").value("New"))
		       .andExpect(jsonPath("$.data.bio").value("NewBio"))
		       .andExpect(jsonPath("$.data.bands").isArray()); // <-- yeni assertion
	}
}