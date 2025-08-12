package com.berkayb.soundconnect.modules.profile.ProducerProfile.controller.user;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.profile.ProducerProfile.dto.request.ProducerProfileSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.ProducerProfile.dto.response.ProducerProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.ProducerProfile.service.ProducerProfileService;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;
import java.util.UUID;

import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = ProducerProfileUserController.class)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@Tag("web")
class ProducerProfileUserControllerTest {
	
	@Autowired MockMvc mockMvc;
	private final ObjectMapper om = new ObjectMapper();
	
	@MockitoBean ProducerProfileService producerProfileService;
	
	@MockitoBean com.berkayb.soundconnect.auth.security.JwtAuthenticationFilter jwtAuthenticationFilter;
	@MockitoBean com.berkayb.soundconnect.auth.security.JwtTokenProvider jwtTokenProvider;
	@MockitoBean com.berkayb.soundconnect.auth.service.CustomUserDetailsService customUserDetailsService;
	
	private UUID userId;
	private UserDetailsImpl principal;
	
	@BeforeEach
	void setUp() {
		userId = UUID.randomUUID();
		User user = new User();
		user.setId(userId);
		user.setUsername("testuser");
		user.setPassword("secret");
		
		principal = Mockito.mock(UserDetailsImpl.class);
		when(principal.getUser()).thenReturn(user);
		
		var auth = new UsernamePasswordAuthenticationToken(principal, "N/A", Collections.emptyList());
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(auth);
		SecurityContextHolder.setContext(context);
	}
	
	@AfterEach
	void tearDown() {
		SecurityContextHolder.clearContext();
	}
	
	@Test
	void getMyProfile_ok() throws Exception {
		var dto = new ProducerProfileResponseDto(
				UUID.randomUUID(), "Mine","desc","pp.png","addr","555","site.com","ig","yt"
		);
		when(producerProfileService.getProfileByUserId(userId)).thenReturn(dto);
		
		mockMvc.perform(get("/api/v1/user/producer-profiles/me"))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success", is(true)))
		       .andExpect(jsonPath("$.code", is(200)))
		       .andExpect(jsonPath("$.data.name").value("Mine"));
	}
	
	@Test
	void updateMyProfile_ok() throws Exception {
		var body = new ProducerProfileSaveRequestDto(
				"Upd","new","pic.png","addr2","111","site2.com","ig2","yt2"
		);
		var dto = new ProducerProfileResponseDto(
				UUID.randomUUID(), "Upd","new","pic.png","addr2","111","site2.com","ig2","yt2"
		);
		when(producerProfileService.updateProfile(userId, body)).thenReturn(dto);
		
		mockMvc.perform(
				       put("/api/v1/user/producer-profiles/update")
						       .contentType(MediaType.APPLICATION_JSON)
						       .content(om.writeValueAsString(body))
		       )
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success", is(true)))
		       .andExpect(jsonPath("$.code", is(200)))
		       .andExpect(jsonPath("$.data.website").value("site2.com"));
	}
}