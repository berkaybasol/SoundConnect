package com.berkayb.soundconnect.modules.profile.ListenerProfile.controller.user;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.abuse.ListenerVisibilityRateLimitGuard;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.request.ListenerSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.request.ListenerAvatarUpdateRequestDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.request.ListenerVisibilityUpdateRequestDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.response.ListenerProfileOwnerResponseDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.service.ListenerProfileService;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.RateLimitedException;
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
	ListenerVisibilityRateLimitGuard visibilityRateLimitGuard;
	
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
		var dto = ownerResponse("my bio", ppId, ListenerVisibilityMode.STANDARD);
		when(listenerProfileService.getMyProfile(userId)).thenReturn(dto);
		
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
		var dto = ownerResponse("hello", ppId, ListenerVisibilityMode.STANDARD);
		
		when(listenerProfileService.createProfile(userId, body)).thenReturn(dto);
		
		mockMvc.perform(post("/api/v1/user/listener-profiles/create")
				                .contentType(MediaType.APPLICATION_JSON)
				                .content(om.writeValueAsString(body)))
		       .andExpect(status().isCreated())
		       .andExpect(jsonPath("$.success", is(true)))
		       .andExpect(jsonPath("$.code", is(201)))
		       .andExpect(jsonPath("$.data.bio").value("hello"))
		       .andExpect(jsonPath("$.data.profilePictureMediaId").value(ppId.toString()));
	}
	
	@Test
	void updateMyProfile_ok() throws Exception {
		UUID ppId = UUID.randomUUID();
		
		var body = new ListenerSaveRequestDto("upd", ppId);
		var dto = ownerResponse("upd", ppId, ListenerVisibilityMode.STANDARD);
		
		when(listenerProfileService.updateMyProfile(userId, body)).thenReturn(dto);
		
		mockMvc.perform(put("/api/v1/user/listener-profiles/update")
				                .contentType(MediaType.APPLICATION_JSON)
				                .content(om.writeValueAsString(body)))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success", is(true)))
		       .andExpect(jsonPath("$.code", is(200)))
		       .andExpect(jsonPath("$.data.bio").value("upd"))
		       .andExpect(jsonPath("$.data.profilePictureMediaId").value(ppId.toString()));
	}

	@Test
	void updateMyAvatarIsAvailableThroughDedicatedPatch() throws Exception {
		UUID ppId = UUID.randomUUID();
		var body = new ListenerAvatarUpdateRequestDto(ppId, 4L);
		var dto = ownerResponse(null, ppId, ListenerVisibilityMode.GHOST);
		when(listenerProfileService.updateAvatar(userId, body)).thenReturn(dto);

		mockMvc.perform(patch("/api/v1/user/listener-profiles/me/avatar")
						.contentType(MediaType.APPLICATION_JSON)
						.content(om.writeValueAsString(body)))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.data.visibilityMode").value("GHOST"))
		       .andExpect(jsonPath("$.data.avatarEditable", is(true)))
		       .andExpect(jsonPath("$.data.profilePictureMediaId").value(ppId.toString()));
	}

	@Test
	void updateMyAvatarRejectsAnEmptyCommandInsteadOfDeletingByAccident() throws Exception {
		mockMvc.perform(patch("/api/v1/user/listener-profiles/me/avatar")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
		       .andExpect(status().isBadRequest());

		Mockito.verifyNoInteractions(listenerProfileService);
	}

	@Test
	void updateMyVisibilityUsesDesiredModeAndExpectedVersion() throws Exception {
		var body = new ListenerVisibilityUpdateRequestDto(ListenerVisibilityMode.GHOST, 3L);
		var dto = ownerResponse(null, null, ListenerVisibilityMode.GHOST);
		when(listenerProfileService.updateVisibility(userId, body)).thenReturn(dto);

		mockMvc.perform(patch("/api/v1/user/listener-profiles/me/visibility")
						.contentType(MediaType.APPLICATION_JSON)
						.content(om.writeValueAsString(body)))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.data.visibilityMode").value("GHOST"))
		       .andExpect(jsonPath("$.data.visibilityChoiceCompleted", is(true)))
		       .andExpect(jsonPath("$.data.profileContentVisible", is(false)))
		       .andExpect(jsonPath("$.data.profileContentEditable", is(false)))
		       .andExpect(jsonPath("$.data.canReceiveFollowers", is(false)))
		       .andExpect(jsonPath("$.data.bio").doesNotExist())
		       .andExpect(jsonPath("$.data.followerCount").doesNotExist())
		       .andExpect(jsonPath("$.data.followingCount").doesNotExist());

		Mockito.verify(visibilityRateLimitGuard).check(userId);
		Mockito.verify(listenerProfileService).updateVisibility(userId, body);
	}

	@Test
	void updateMyVisibilityIsRateLimitedBeforeTheTransactionalServiceCall() throws Exception {
		var body = new ListenerVisibilityUpdateRequestDto(ListenerVisibilityMode.GHOST, 3L);
		Mockito.doThrow(new RateLimitedException(
				ErrorType.LISTENER_PROFILE_VISIBILITY_RATE_LIMITED, 8L))
				.when(visibilityRateLimitGuard).check(userId);

		mockMvc.perform(patch("/api/v1/user/listener-profiles/me/visibility")
						.contentType(MediaType.APPLICATION_JSON)
						.content(om.writeValueAsString(body)))
		       .andExpect(status().isTooManyRequests())
		       .andExpect(header().string("Retry-After", "8"))
		       .andExpect(jsonPath("$.code", is(1306)));

		Mockito.verifyNoInteractions(listenerProfileService);
	}

	private ListenerProfileOwnerResponseDto ownerResponse(
			String bio,
			UUID profilePictureMediaId,
			ListenerVisibilityMode mode
	) {
		boolean ghost = mode == ListenerVisibilityMode.GHOST;
		return new ListenerProfileOwnerResponseDto(
				UUID.randomUUID(), userId, "testuser", mode, true, 4L, null, bio,
				profilePictureMediaId, "https://cdn.example.com/profile.jpg",
				ghost ? null : 0L, ghost ? null : 0L,
				!ghost, !ghost, true, !ghost);
	}
}
