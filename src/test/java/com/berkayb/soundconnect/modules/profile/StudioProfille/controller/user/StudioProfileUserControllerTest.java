package com.berkayb.soundconnect.modules.profile.StudioProfille.controller.user;

import com.berkayb.soundconnect.auth.security.JwtTokenProvider;
import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.auth.service.CustomUserDetailsService;
import com.berkayb.soundconnect.modules.profile.StudioProfile.controller.user.StudioProfileUserController;
import com.berkayb.soundconnect.modules.profile.StudioProfile.dto.request.StudioProfileSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.StudioProfile.dto.response.StudioProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.StudioProfile.service.StudioProfileService;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = StudioProfileUserController.class)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@Import(StudioProfileUserControllerTest.TestMvcConfig.class)
class StudioProfileUserControllerTest {
	
	@MockitoBean StudioProfileService studioProfileService;
	
	// security tarafını sustur
	@MockitoBean JwtTokenProvider jwtTokenProvider;
	@MockitoBean CustomUserDetailsService customUserDetailsService;
	@MockitoBean com.berkayb.soundconnect.shared.util.JwtUtil jwtUtil;
	
	@Autowired MockMvc mockMvc;
	@Autowired ObjectMapper objectMapper;
	
	// Resolver’ın enjekte edeceği principal
	@Autowired UserDetailsImpl testPrincipal;
	
	private UUID userId;
	
	@BeforeEach
	void setUp() {
		userId = UUID.randomUUID();
		var user = new User();
		user.setId(userId);
		when(testPrincipal.getUser()).thenReturn(user);
	}
	
	@Test
	void getMyProfile_ok() throws Exception {
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
		
		mockMvc.perform(get("/api/v1/user/studio-profiles/me"))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success", is(true)))
		       .andExpect(jsonPath("$.code", is(200)))
		       .andExpect(jsonPath("$.data.name").value("S1"))
		       .andExpect(jsonPath("$.data.facilities[0]").value("mixing"));
	}
	
	@Test
	void updateMyProfile_ok() throws Exception {
		var req = new StudioProfileSaveRequestDto(
				"New", "d", "new.png", "new addr", "555",
				"site.com", Set.of("parking"), "ig", "yt"
		);
		var resp = new StudioProfileResponseDto(
				UUID.randomUUID(),
				"New", "d", "new.png",
				"new addr", "555", "site.com",
				Set.of("parking"), "ig", "yt"
		);
		
		when(studioProfileService.updateProfile(userId, req)).thenReturn(resp);
		
		mockMvc.perform(put("/api/v1/user/studio-profiles/update")
				                .contentType(MediaType.APPLICATION_JSON)
				                .content(objectMapper.writeValueAsString(req)))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.data.name").value("New"));
	}
	
	/** @AuthenticationPrincipal(UserDetailsImpl) için test konfigurasyonu */
	@TestConfiguration
	static class TestMvcConfig implements WebMvcConfigurer {
		
		@Bean
		UserDetailsImpl testPrincipal() {
			// deep stubs -> principal.getUser().getId() zinciri rahatça stub’lanır
			return org.mockito.Mockito.mock(UserDetailsImpl.class, Answers.RETURNS_DEEP_STUBS);
		}
		
		@Bean
		HandlerMethodArgumentResolver userDetailsImplResolver(UserDetailsImpl principal) {
			return new HandlerMethodArgumentResolver() {
				@Override
				public boolean supportsParameter(MethodParameter parameter) {
					return UserDetailsImpl.class.isAssignableFrom(parameter.getParameterType());
				}
				
				@Override
				public Object resolveArgument(MethodParameter parameter,
				                              ModelAndViewContainer mavContainer,
				                              NativeWebRequest webRequest,
				                              WebDataBinderFactory binderFactory) {
					return principal;
				}
			};
		}
		
		// *** KRİTİK *** -> resolver’ı MVC’ye kaydet
		@Override
		public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
			resolvers.add(userDetailsImplResolver(testPrincipal()));
		}
	}
}