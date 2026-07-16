package com.berkayb.soundconnect.modules.profile.ListenerProfile.controller.user;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.request.ListenerSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.response.ListenerProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.service.ListenerProfileService;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
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

import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = ListenerProfileUserController.class)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@Tag("web")
class ListenerProfileUserControllerTest {
	
	@Autowired
	MockMvc mockMvc;
	
	private final ObjectMapper om = new ObjectMapper();
	
	@MockitoBean
	ListenerProfileService listenerProfileService;
	
	@MockitoBean
	com.berkayb.soundconnect.auth.security.JwtAuthenticationFilter jwtAuthenticationFilter;
	@MockitoBean
	com.berkayb.soundconnect.auth.security.JwtTokenProvider jwtTokenProvider;
	@MockitoBean
	com.berkayb.soundconnect.auth.service.CustomUserDetailsService customUserDetailsService;
	
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
		var context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(auth);
		SecurityContextHolder.setContext(context);
	}
	
	@AfterEach
	void tearDown() {
		SecurityContextHolder.clearContext();
	}
	
	@Test
	void getMyProfile_ok() throws Exception {
		UUID ppId = UUID.randomUUID();
		var dto = new ListenerProfileResponseDto(
				UUID.randomUUID(), userId, "testuser", "my bio", ppId,
				"https://cdn.example.com/profile.jpg", 0, 0);
		when(listenerProfileService.getProfileByUserId(userId)).thenReturn(dto);
		
		mockMvc.perform(get("/api/v1/user/listener-profiles/me"))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success", is(true)))
		       .andExpect(jsonPath("$.code", is(200)))
		       .andExpect(jsonPath("$.data.userId").value(userId.toString()))
		       .andExpect(jsonPath("$.data.profilePictureMediaId").value(ppId.toString()));
	}
	
	@Test
	void createMyProfile_ok() throws Exception {
		UUID ppId = UUID.randomUUID();
		
		var body = new ListenerSaveRequestDto("hello", ppId);
		var dto = new ListenerProfileResponseDto(
				UUID.randomUUID(), userId, "testuser", "hello", ppId,
				"https://cdn.example.com/profile.jpg", 0, 0);
		
		when(listenerProfileService.createProfile(userId, body)).thenReturn(dto);
		
		mockMvc.perform(post("/api/v1/user/listener-profiles/create")
				                .contentType(MediaType.APPLICATION_JSON)
				                .content(om.writeValueAsString(body)))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success", is(true)))
		       .andExpect(jsonPath("$.code", is(201)))
		       .andExpect(jsonPath("$.data.bio").value("hello"))
		       .andExpect(jsonPath("$.data.profilePictureMediaId").value(ppId.toString()));
	}
	
	@Test
	void updateMyProfile_ok() throws Exception {
		UUID ppId = UUID.randomUUID();
		
		var body = new ListenerSaveRequestDto("upd", ppId);
		var dto = new ListenerProfileResponseDto(
				UUID.randomUUID(), userId, "testuser", "upd", ppId,
				"https://cdn.example.com/profile.jpg", 0, 0);
		
		when(listenerProfileService.updateProfile(userId, body)).thenReturn(dto);
		
		mockMvc.perform(put("/api/v1/user/listener-profiles/update")
				                .contentType(MediaType.APPLICATION_JSON)
				                .content(om.writeValueAsString(body)))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success", is(true)))
		       .andExpect(jsonPath("$.code", is(200)))
		       .andExpect(jsonPath("$.data.bio").value("upd"))
		       .andExpect(jsonPath("$.data.profilePictureMediaId").value(ppId.toString()));
	}
}
