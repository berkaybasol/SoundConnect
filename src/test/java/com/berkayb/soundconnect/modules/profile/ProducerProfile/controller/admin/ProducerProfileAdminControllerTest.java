package com.berkayb.soundconnect.modules.profile.ProducerProfile.controller.admin;

import com.berkayb.soundconnect.modules.profile.ProducerProfile.dto.request.ProducerProfileSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.ProducerProfile.dto.response.ProducerProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.ProducerProfile.service.ProducerProfileService;
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

@WebMvcTest(controllers = ProducerProfileAdminController.class)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@Tag("web")
class ProducerProfileAdminControllerTest {
	
	@Autowired MockMvc mockMvc;
	private final ObjectMapper om = new ObjectMapper();
	
	@MockitoBean ProducerProfileService producerProfileService;
	
	// Security bean’lerini mockla
	@MockitoBean com.berkayb.soundconnect.auth.security.JwtAuthenticationFilter jwtAuthenticationFilter;
	@MockitoBean com.berkayb.soundconnect.auth.security.JwtTokenProvider jwtTokenProvider;
	@MockitoBean com.berkayb.soundconnect.auth.service.CustomUserDetailsService customUserDetailsService;
	
	@Test
	void getProducerProfileByUserId_ok() throws Exception {
		UUID userId = UUID.randomUUID();
		var dto = new ProducerProfileResponseDto(
				UUID.randomUUID(), "ProdX", "desc", "pp.png", "addr", "555",
				"site.com", "ig", "yt"
		);
		
		when(producerProfileService.getProfileByUserId(userId)).thenReturn(dto);
		
		mockMvc.perform(get("/api/v1/admin/producer-profiles/by-user/{userId}", userId))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success", is(true)))
		       .andExpect(jsonPath("$.code", is(200)))
		       .andExpect(jsonPath("$.data.name").value("ProdX"));
	}
	
	@Test
	void updateProducerProfile_ok() throws Exception {
		UUID userId = UUID.randomUUID();
		var body = new ProducerProfileSaveRequestDto(
				"NewName","newdesc","pic.png","newaddr","111","site2.com","ig2","yt2"
		);
		var dto = new ProducerProfileResponseDto(
				UUID.randomUUID(), "NewName","newdesc","pic.png","newaddr","111","site2.com","ig2","yt2"
		);
		
		when(producerProfileService.updateProfile(userId, body)).thenReturn(dto);
		
		mockMvc.perform(
				       put("/api/v1/admin/producer-profiles/by-user/{userId}/update", userId)
						       .contentType(MediaType.APPLICATION_JSON)
						       .content(om.writeValueAsString(body))
		       )
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success", is(true)))
		       .andExpect(jsonPath("$.code", is(200)))
		       .andExpect(jsonPath("$.data.website").value("site2.com"));
	}
}