package com.berkayb.soundconnect.modules.profile.shared.media.controller;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.profile.shared.media.dto.request.ProfileMediaAddRequestDto;
import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileMediaRole;
import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileType;
import com.berkayb.soundconnect.modules.profile.shared.media.service.ProfileMediaService;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;
import java.util.UUID;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ProfileMediaManagementController.class)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@Tag("web")
class ProfileMediaManagementControllerTest {

	@Autowired MockMvc mockMvc;
	@Autowired ObjectMapper objectMapper;

	@MockitoBean ProfileMediaService profileMediaService;
	@MockitoBean com.berkayb.soundconnect.auth.security.JwtAuthenticationFilter jwtAuthenticationFilter;
	@MockitoBean com.berkayb.soundconnect.auth.security.JwtTokenProvider jwtTokenProvider;
	@MockitoBean com.berkayb.soundconnect.auth.service.CustomUserDetailsService customUserDetailsService;

	private UUID userId;

	@BeforeEach
	void authenticate() {
		userId = UUID.randomUUID();
		User user = User.builder().id(userId).username("retry-user").password("unused").build();
		UserDetailsImpl principal = Mockito.mock(UserDetailsImpl.class);
		Mockito.when(principal.getUser()).thenReturn(user);
		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken(principal, "N/A", Collections.emptyList()));
	}

	@AfterEach
	void clearAuthentication() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void repeatedPostReceivesTheSameSuccessContract() throws Exception {
		ProfileMediaAddRequestDto request = new ProfileMediaAddRequestDto(
				ProfileType.LISTENER,
				UUID.randomUUID(),
				UUID.randomUUID(),
				ProfileMediaRole.GALLERY,
				0);
		String json = objectMapper.writeValueAsString(request);

		for (int attempt = 0; attempt < 2; attempt++) {
			mockMvc.perform(post("/api/v1/profile-media")
						.contentType(MediaType.APPLICATION_JSON)
						.content(json))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.success").value(true))
					.andExpect(jsonPath("$.code").value(200))
					.andExpect(jsonPath("$.message").value("Media profile eklendi"));
		}

		verify(profileMediaService, times(2)).addMedia(
				userId,
				request.profileType(),
				request.profileId(),
				request.mediaAssetId(),
				request.role(),
				request.orderIndex());
	}
}
