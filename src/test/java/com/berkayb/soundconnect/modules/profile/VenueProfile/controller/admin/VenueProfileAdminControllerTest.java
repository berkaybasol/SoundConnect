package com.berkayb.soundconnect.modules.profile.VenueProfile.controller.admin;

import com.berkayb.soundconnect.modules.profile.VenueProfile.dto.request.VenueProfileSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.VenueProfile.dto.response.VenueProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.VenueProfile.service.VenueProfileService;
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

import java.util.List;
import java.util.UUID;

import static com.berkayb.soundconnect.shared.constant.EndPoints.VenueProfile.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = VenueProfileAdminController.class)
@AutoConfigureMockMvc(addFilters = false)
@Tag("web")
class VenueProfileAdminControllerIT {
	
	@Autowired MockMvc mockMvc;
	@Autowired ObjectMapper om;
	
	@MockitoBean
	private VenueProfileService venueProfileService;
	
	// Security bean’leri context’te isteniyorsa diye (filters kapalı ama bean gerekebiliyor)
	@MockitoBean
	private com.berkayb.soundconnect.auth.security.JwtTokenProvider jwtTokenProvider;
	
	@MockitoBean
	private com.berkayb.soundconnect.auth.security.JwtAuthenticationFilter jwtAuthenticationFilter;
	
	@Test
	void getVenueProfilesByUserId_ok() throws Exception {
		UUID userId = UUID.randomUUID();
		
		UUID venueId1 = UUID.randomUUID();
		UUID venueId2 = UUID.randomUUID();
		
		var p1 = new VenueProfileResponseDto(
				UUID.randomUUID(),
				venueId1,
				"Venue One",
				"bio one",
				null,
				"ig1",
				"yt1",
				"web1"
		);
		
		var p2 = new VenueProfileResponseDto(
				UUID.randomUUID(),
				venueId2,
				"Venue Two",
				"bio two",
				null,
				"ig2",
				"yt2",
				"web2"
		);
		
		Mockito.when(venueProfileService.getProfilesByUserId(userId)).thenReturn(List.of(p1, p2));
		
		mockMvc.perform(get(ADMIN_BASE + BY_USER_ID, userId))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.code").value(200))
		       .andExpect(jsonPath("$.data.length()").value(2))
		       .andExpect(jsonPath("$.data[0].venueId").value(venueId1.toString()))
		       .andExpect(jsonPath("$.data[1].venueId").value(venueId2.toString()));
	}
	
	@Test
	void adminUpdateProfile_ok() throws Exception {
		UUID userId = UUID.randomUUID();
		UUID venueId = UUID.randomUUID();
		UUID profileId = UUID.randomUUID();
		UUID ppId = UUID.randomUUID();
		
		var req = new VenueProfileSaveRequestDto(
				"new bio by admin",
				ppId,
				"https://ig/new-admin",
				"https://yt/new",
				"https://site/new"
		);
		
		var resp = new VenueProfileResponseDto(
				profileId,
				venueId,
				"Venue One",
				"new bio by admin",
				ppId,
				"https://ig/new-admin",
				"https://yt/new",
				"https://site/new"
		);
		
		Mockito.when(venueProfileService.updateProfileByVenueId(eq(userId), eq(venueId), any()))
		       .thenReturn(resp);
		
		mockMvc.perform(
				       put(ADMIN_BASE + ADMIN_UPDATE, userId, venueId)
						       .contentType(MediaType.APPLICATION_JSON)
						       .content(om.writeValueAsString(req))
		       )
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.code").value(200))
		       .andExpect(jsonPath("$.data.venueId").value(venueId.toString()))
		       .andExpect(jsonPath("$.data.bio").value("new bio by admin"))
		       .andExpect(jsonPath("$.data.instagramUrl").value("https://ig/new-admin"))
		       .andExpect(jsonPath("$.data.youtubeUrl").value("https://yt/new"))
		       .andExpect(jsonPath("$.data.websiteUrl").value("https://site/new"));
	}
	
	@Test
	void adminCreateProfile_ok() throws Exception {
		UUID venueId = UUID.randomUUID();
		UUID profileId = UUID.randomUUID();
		UUID ppId = UUID.randomUUID();
		
		var req = new VenueProfileSaveRequestDto(
				"fresh bio",
				ppId,
				"https://ig/fresh",
				null,
				"https://fresh.site"
		);
		
		var resp = new VenueProfileResponseDto(
				profileId,
				venueId,
				"Venue Two",
				"fresh bio",
				ppId,
				"https://ig/fresh",
				null,
				"https://fresh.site"
		);
		
		Mockito.when(venueProfileService.createProfile(eq(venueId), any())).thenReturn(resp);
		
		mockMvc.perform(
				       post(ADMIN_BASE + ADMIN_CREATE, venueId)
						       .contentType(MediaType.APPLICATION_JSON)
						       .content(om.writeValueAsString(req))
		       )
		       .andExpect(status().isOk())               // HTTP 200
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.code").value(201)) // body.code 201
		       .andExpect(jsonPath("$.data.venueId").value(venueId.toString()))
		       .andExpect(jsonPath("$.data.bio").value("fresh bio"))
		       .andExpect(jsonPath("$.data.instagramUrl").value("https://ig/fresh"))
		       .andExpect(jsonPath("$.data.websiteUrl").value("https://fresh.site"));
	}
}