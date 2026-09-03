package com.berkayb.soundconnect.modules.profile.ListenerProfile.controller.publicapi;

import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.response.ListenerProfilePublicResponseDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.service.ListenerProfileService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ListenerProfilePublicController.class)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@Tag("web")
class ListenerProfilePublicControllerTest {

	@Autowired MockMvc mockMvc;
	@MockitoBean ListenerProfileService listenerProfileService;
	@MockitoBean com.berkayb.soundconnect.auth.security.JwtAuthenticationFilter jwtAuthenticationFilter;
	@MockitoBean com.berkayb.soundconnect.auth.security.JwtTokenProvider jwtTokenProvider;
	@MockitoBean com.berkayb.soundconnect.auth.service.CustomUserDetailsService customUserDetailsService;

	@Test
	void ghostProfileReturnsRestrictedIdentityWithoutHiddenFields() throws Exception {
		UUID profileId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		var response = new ListenerProfilePublicResponseDto(
				profileId,
				userId,
				"ghostlistener",
				ListenerVisibilityMode.GHOST,
				null,
				UUID.randomUUID(),
				"https://cdn.test/avatar.jpg",
				null,
				null,
				true,
				false,
				true
		);
		when(listenerProfileService.getProfileByProfileId(profileId)).thenReturn(response);

		mockMvc.perform(get("/api/v1/public/listener-profiles/{profileId}", profileId))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.data.userId").value(userId.toString()))
		       .andExpect(jsonPath("$.data.username").value("ghostlistener"))
		       .andExpect(jsonPath("$.data.profilePictureUrl").value("https://cdn.test/avatar.jpg"))
		       .andExpect(jsonPath("$.data.visibilityMode").value("GHOST"))
		       .andExpect(jsonPath("$.data.restricted", is(true)))
		       .andExpect(jsonPath("$.data.canFollow", is(false)))
		       .andExpect(jsonPath("$.data.canMessage", is(true)))
		       .andExpect(jsonPath("$.data.bio").doesNotExist())
		       .andExpect(jsonPath("$.data.followerCount").doesNotExist())
		       .andExpect(jsonPath("$.data.followingCount").doesNotExist());
	}
}
