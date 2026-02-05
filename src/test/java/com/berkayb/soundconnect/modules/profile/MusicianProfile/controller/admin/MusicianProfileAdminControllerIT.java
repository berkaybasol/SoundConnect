package com.berkayb.soundconnect.modules.profile.MusicianProfile.controller.admin;

import com.berkayb.soundconnect.modules.profile.MusicianProfile.dto.request.MusicianProfileSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.dto.response.MusicianProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.service.MusicianProfileService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

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
	
	// Security bean’leri context’te isteniyorsa diye (filters kapalı ama bean gerekebiliyor)
	@MockitoBean
	private com.berkayb.soundconnect.auth.security.JwtTokenProvider jwtTokenProvider;
	
	@MockitoBean
	private com.berkayb.soundconnect.auth.security.JwtAuthenticationFilter jwtAuthenticationFilter;
	
	@Test
	void getMusicianProfileByUserId_ok() throws Exception {
		var uid = UUID.randomUUID();
		
		var resp = new MusicianProfileResponseDto(
				UUID.randomUUID(),     // id
				uid,                   // userId
				"Stage",               // stageName
				"Bio",                 // bio
				null,                  // profilePictureMediaId
				null,                  // instagramUrl
				null,                  // youtubeUrl
				null,                  // soundcloudUrl
				null,                  // spotifyEmbedUrl
				null,                  // spotifyArtistId
				Set.of(),              // instruments
				Set.of(),              // activeVenues
				Set.of()               // bands
		);
		
		Mockito.when(service.getProfileByUserId(uid)).thenReturn(resp);
		
		mockMvc.perform(get(ADMIN_BASE + BY_USER_ID, uid))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.data.stageName").value("Stage"))
		       .andExpect(jsonPath("$.data.bio").value("Bio"))
		       .andExpect(jsonPath("$.data.bands").isArray());
	}
	
	@Test
	void updateMusicianProfileByUserId_ok() throws Exception {
		var uid = UUID.randomUUID();
		
		var req = new MusicianProfileSaveRequestDto(
				"New",          // stageName
				"NewBio",       // description
				null,           // profilePicture
				null,           // instagramUrl
				null,           // youtubeUrl
				null,           // soundcloudUrl
				null,           // spotifyEmbedUrl
				null,           // spotifyArtistId
				null            // instrumentIds
		);
		
		var resp = new MusicianProfileResponseDto(
				UUID.randomUUID(), // id
				uid,               // userId
				"New",             // stageName
				"NewBio",          // bio
				null,
				null,
				null,
				null,
				null,
				null,
				Set.of(),
				Set.of(),
				Set.of()
		);
		
		Mockito.when(service.updateProfile(eq(uid), any())).thenReturn(resp);
		
		mockMvc.perform(
				       put(ADMIN_BASE + ADMIN_UPDATE, uid)
						       .contentType(MediaType.APPLICATION_JSON)
						       .content(om.writeValueAsString(req))
		       )
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.data.stageName").value("New"))
		       .andExpect(jsonPath("$.data.bio").value("NewBio"))
		       .andExpect(jsonPath("$.data.bands").isArray());
	}
}