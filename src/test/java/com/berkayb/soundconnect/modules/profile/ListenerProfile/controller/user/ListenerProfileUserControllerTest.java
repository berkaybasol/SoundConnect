package com.berkayb.soundconnect.modules.profile.ListenerProfile.controller.user;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.abuse.ListenerPlaylistRateLimitGuard;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.abuse.ListenerVisibilityRateLimitGuard;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.request.ListenerSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.request.ListenerAvatarUpdateRequestDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.request.ListenerVisibilityUpdateRequestDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.request.ListenerPlaylistsUpdateRequestDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.response.ListenerPlaylistResponseDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.response.ListenerProfileOwnerResponseDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.service.ListenerProfileService;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.service.ListenerPlaylistService;
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
	ListenerPlaylistService listenerPlaylistService;

	@MockitoBean
	ListenerPlaylistRateLimitGuard playlistRateLimitGuard;

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
		       .andExpect(jsonPath("$.data.followingCount").doesNotExist())
		       .andExpect(jsonPath("$.data.playlists").isArray())
		       .andExpect(jsonPath("$.data.playlists").isEmpty());

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

	@Test
	void replaceMyPlaylistsUsesTheAuthenticatedListenerAndReturnsTheFullOwnerProfile() throws Exception {
		String playlistId = "37i9dQZF1DXcBWIGoYBM5M";
		String playlistUrl = "https://open.spotify.com/playlist/" + playlistId;
		var body = new ListenerPlaylistsUpdateRequestDto(java.util.List.of(playlistUrl), 4L);
		var playlist = new ListenerPlaylistResponseDto(
				UUID.randomUUID(), playlistId, "Today's Top Hits",
				"https://i.scdn.co/image/cover", playlistUrl, 0);
		var response = new ListenerProfileOwnerResponseDto(
				UUID.randomUUID(), userId, "testuser", ListenerVisibilityMode.STANDARD,
				true, 5L, null, "bio", null, null, 0L, 0L,
				true, true, true, true, java.util.List.of(playlist));
		when(listenerPlaylistService.replacePlaylists(userId, body)).thenReturn(response);

		mockMvc.perform(put("/api/v1/user/listener-profiles/me/playlists")
						.contentType(MediaType.APPLICATION_JSON)
						.content(om.writeValueAsString(body)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.version", is(5)))
				.andExpect(jsonPath("$.data.playlists[0].spotifyPlaylistId").value(playlistId))
				.andExpect(jsonPath("$.data.playlists[0].title").value("Today's Top Hits"))
				.andExpect(jsonPath("$.data.playlists[0].position", is(0)));

		var inOrder = Mockito.inOrder(playlistRateLimitGuard, listenerPlaylistService);
		inOrder.verify(playlistRateLimitGuard).check(userId);
		inOrder.verify(listenerPlaylistService).replacePlaylists(userId, body);
	}

	@Test
	void replaceMyPlaylistsIsRateLimitedBeforeSpotifyOrPersistenceOrchestration() throws Exception {
		var body = new ListenerPlaylistsUpdateRequestDto(java.util.List.of(
				"https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"), 4L);
		Mockito.doThrow(new RateLimitedException(
				ErrorType.LISTENER_PLAYLIST_RATE_LIMITED, 37L))
				.when(playlistRateLimitGuard).check(userId);

		mockMvc.perform(put("/api/v1/user/listener-profiles/me/playlists")
						.contentType(MediaType.APPLICATION_JSON)
						.content(om.writeValueAsString(body)))
				.andExpect(status().isTooManyRequests())
				.andExpect(header().string("Retry-After", "37"))
				.andExpect(jsonPath("$.code", is(1310)))
				.andExpect(jsonPath("$.details[0]").value(
						"Çalma listelerini çok sık güncellemeye çalıştınız. Lütfen kısa süre sonra tekrar deneyin."));

		Mockito.verifyNoInteractions(listenerPlaylistService);
	}

	@Test
	void replaceMyPlaylistsRejectsMoreThanFourUrlsAtTheWebBoundary() throws Exception {
		mockMvc.perform(put("/api/v1/user/listener-profiles/me/playlists")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "spotifyUrls":["a","b","c","d","e"],
								  "expectedVersion":0
								}
								"""))
				.andExpect(status().isBadRequest());

		Mockito.verifyNoInteractions(playlistRateLimitGuard, listenerPlaylistService);
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
				!ghost, !ghost, true, !ghost, java.util.List.of());
	}
}
