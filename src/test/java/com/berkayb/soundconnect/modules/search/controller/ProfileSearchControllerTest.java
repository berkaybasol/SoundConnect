package com.berkayb.soundconnect.modules.search.controller;

import com.berkayb.soundconnect.modules.search.enums.ProfileSearchType;
import com.berkayb.soundconnect.modules.search.service.ProfileSearchService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
		controllers = ProfileSearchController.class,
		excludeFilters = @ComponentScan.Filter(
				type = FilterType.ASSIGNABLE_TYPE,
				classes = com.berkayb.soundconnect.auth.security.JwtAuthenticationFilter.class
		)
)
@AutoConfigureMockMvc(addFilters = false)
class ProfileSearchControllerTest {
	@Autowired MockMvc mockMvc;
	@MockitoBean ProfileSearchService profileSearchService;

	@Test
	void forwardsPerformerTypeScopeAsAnExplicitApiContract() throws Exception {
		Set<ProfileSearchType> types = Set.of(ProfileSearchType.MUSICIAN, ProfileSearchType.BAND);
		when(profileSearchService.searchProfiles("sah", 20, types)).thenReturn(List.of());

		mockMvc.perform(get("/api/v1/public/search/profiles")
					.param("q", "sah")
					.param("limit", "20")
					.param("types", "MUSICIAN,BAND"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data").isArray());

		verify(profileSearchService).searchProfiles("sah", 20, types);
	}

	@Test
	void rejectsUnknownProfileType() throws Exception {
		mockMvc.perform(get("/api/v1/public/search/profiles")
					.param("q", "sah")
					.param("types", "MUSICIAN,NOT_A_PROFILE"))
				.andExpect(status().isBadRequest());
	}
}
