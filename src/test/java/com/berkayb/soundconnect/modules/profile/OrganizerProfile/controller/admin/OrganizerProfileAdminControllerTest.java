package com.berkayb.soundconnect.modules.profile.OrganizerProfile.controller.admin;

import com.berkayb.soundconnect.modules.profile.OrganizerProfile.dto.request.OrganizerProfileSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.OrganizerProfile.dto.response.OrganizerProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.OrganizerProfile.service.OrganizerProfileService;
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

import java.util.UUID;

import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = OrganizerProfileAdminController.class)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@Tag("web")
class OrganizerProfileAdminControllerTest {
	
	@Autowired MockMvc mockMvc;
	private final ObjectMapper om = new ObjectMapper();
	
	@MockitoBean OrganizerProfileService organizerProfileService;
	
	// Security bean’lerini mockla (context sorunsuz yüklensin)
	@MockitoBean com.berkayb.soundconnect.auth.security.JwtAuthenticationFilter jwtAuthenticationFilter;
	@MockitoBean com.berkayb.soundconnect.auth.security.JwtTokenProvider jwtTokenProvider;
	@MockitoBean com.berkayb.soundconnect.auth.service.CustomUserDetailsService customUserDetailsService;
	
	@Test
	void getOrganizerProfileByUserId_ok() throws Exception {
		UUID userId = UUID.randomUUID();
		var dto = new OrganizerProfileResponseDto(
				UUID.randomUUID(), "OrgX", "desc", "pp.png", "addr", "555",
				"ig", "yt"
		);
		
		when(organizerProfileService.getProfileByUserId(userId)).thenReturn(dto);
		
		mockMvc.perform(get("/api/v1/admin/organizer-profiles/by-user/{userId}", userId))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success", is(true)))
		       .andExpect(jsonPath("$.code", is(200)))
		       .andExpect(jsonPath("$.data.name").value("OrgX"));
	}
	
	@Test
	void updateOrganizerProfileByUserId_ok() throws Exception {
		UUID userId = UUID.randomUUID();
		var body = new OrganizerProfileSaveRequestDto(
				"NewName","newdesc","pic.png","newaddr","111","ig2","yt2"
		);
		var dto = new OrganizerProfileResponseDto(
				UUID.randomUUID(), "NewName","newdesc","pic.png","newaddr","111","ig2","yt2"
		);
		
		when(organizerProfileService.updateProfile(userId, body)).thenReturn(dto);
		
		mockMvc.perform(
				       put("/api/v1/admin/organizer-profiles/by-user/{userId}/update", userId)
						       .contentType(MediaType.APPLICATION_JSON)
						       .content(om.writeValueAsString(body))
		       )
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success", is(true)))
		       .andExpect(jsonPath("$.code", is(200)))
		       .andExpect(jsonPath("$.data.name").value("NewName"));
	}
}