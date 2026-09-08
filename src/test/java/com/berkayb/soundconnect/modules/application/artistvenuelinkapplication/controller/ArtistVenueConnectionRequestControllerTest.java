package com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.controller;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.dto.request.ArtistVenueConnectionRequestCreateDto;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.dto.response.ArtistVenueConnectionRequestResponseDto;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.dto.response.ArtistVenueConnectionRequestPageItemDto;
import com.berkayb.soundconnect.shared.response.PageResponse;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.enums.RequestByType;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.enums.RequestStatus;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.service.ArtistVenueConnectionRequestService;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.berkayb.soundconnect.auth.security.JwtAuthenticationFilter;

/**
 * ArtistVenueConnectionRequestControllerImpl Web katmanı testi
 * Sadece controller ve JSON sözleşmesini doğrular, service mock'lanır.
 */
@WebMvcTest(controllers = ArtistVenueConnectionRequestControllerImpl.class)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@Tag("web")
class ArtistVenueConnectionRequestControllerTest {
	
	private static final String BASE = "/api/v1/artist-venue-connections";
	
	@Autowired MockMvc mockMvc;
	@MockitoBean
	ArtistVenueConnectionRequestService service;
	@Autowired ObjectMapper om;
	
	@MockitoBean
	JwtAuthenticationFilter jwtAuthenticationFilter;
	
	UUID requestId;
	UUID musicianProfileId;
	UUID venueId;
	UUID actingUserId;
	ArtistVenueConnectionRequestResponseDto sample;

	@Test
	void overlongMessageIsRejectedBeforeTheServiceInsteadOfFailingDatabaseStorage() throws Exception {
		var dto = new ArtistVenueConnectionRequestCreateDto(musicianProfileId, null, venueId, "x".repeat(256));
		mockMvc.perform(post(BASE + "/request").param("requestByType", "ARTIST")
					.contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(dto)))
				.andExpect(status().isBadRequest());
		verifyNoInteractions(service);
	}

	@Test
	void privatePageEndpointUsesStableEnvelopeAndForwardsDirectionAndBounds() throws Exception {
		var row = ArtistVenueConnectionRequestPageItemDto.from(sample, "https://cdn.test/musician", "current_user", "Current Stage");
		when(service.getMusicianPage(actingUserId, musicianProfileId, RequestStatus.PENDING, true, 2, 10))
				.thenReturn(new PageResponse<>(List.of(row), 2, 10, 21, 3, false, true));
		mockMvc.perform(get(BASE + "/musician/{id}/page", musicianProfileId)
					.param("status", "PENDING").param("incoming", "true").param("page", "2").param("size", "10"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.page").value(2))
				.andExpect(jsonPath("$.data.number").value(2))
				.andExpect(jsonPath("$.data.totalElements").value(21))
				.andExpect(jsonPath("$.data.last").value(true))
				.andExpect(jsonPath("$.data.content[0].musicianDisplayName").value("Current Stage"))
				.andExpect(jsonPath("$.data.content[0].musicianProfilePictureUrl").value("https://cdn.test/musician"));
		verify(service).getMusicianPage(actingUserId, musicianProfileId, RequestStatus.PENDING, true, 2, 10);
	}

	@Test
	void venuePageDefaultsPreserveBothDirections() throws Exception {
		when(service.getVenuePage(actingUserId, venueId, null, null, 0, 20))
				.thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0, true, true));
		mockMvc.perform(get(BASE + "/venue/{id}/page", venueId))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.content").isEmpty());
		verify(service).getVenuePage(actingUserId, venueId, null, null, 0, 20);
	}
	
	@BeforeEach
	void setUp() {
		requestId = UUID.randomUUID();
		musicianProfileId = UUID.randomUUID();
		venueId = UUID.randomUUID();
		actingUserId = UUID.randomUUID();
		
		sample = new ArtistVenueConnectionRequestResponseDto(
				requestId,
				musicianProfileId,
				null,
				venueId,
				"StageNameX",
				null,
				null,
				"VenueX",
				"see you!",
				"PENDING",
				RequestByType.ARTIST,
				"2025-01-01T12:00:00Z"
		);

		User user = User.builder()
		                .id(actingUserId)
		                .username("musician")
		                .password("unused")
		                .email("musician@example.com")
		                .emailVerified(true)
		                .status(UserStatus.ACTIVE)
		                .roles(Set.of(Role.builder().name("ROLE_MUSICIAN").build()))
		                .build();
		UserDetailsImpl principal = UserDetailsImpl.fromUser(user);
		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities())
		);
	}

	@AfterEach
	void tearDown() {
		SecurityContextHolder.clearContext();
	}
	
	@Test
	void createRequest_ok() throws Exception {
		when(service.createRequest(eq(actingUserId), any(), eq(RequestByType.ARTIST))).thenReturn(sample);
		
		var body = new ArtistVenueConnectionRequestCreateDto(musicianProfileId, null, venueId, "see you!");
		mockMvc.perform(post(BASE + "/request")
				                .param("requestByType", "ARTIST")
				                .contentType(MediaType.APPLICATION_JSON)
				                .content(om.writeValueAsBytes(body)))
		       .andExpect(status().isOk())
		       .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.message").value("Basvuru basariyla olusturuldu."))
		       .andExpect(jsonPath("$.data.id").value(requestId.toString()))
		       .andExpect(jsonPath("$.data.musicianProfileId").value(musicianProfileId.toString()))
		       .andExpect(jsonPath("$.data.venueId").value(venueId.toString()))
		       .andExpect(jsonPath("$.data.status").value("PENDING"))
		       .andExpect(jsonPath("$.data.requestByType").value("ARTIST"));
		
		// service'e doğru argüman gitti mi?
		ArgumentCaptor<ArtistVenueConnectionRequestCreateDto> captor = ArgumentCaptor.forClass(ArtistVenueConnectionRequestCreateDto.class);
		verify(service).createRequest(eq(actingUserId), captor.capture(), eq(RequestByType.ARTIST));
		var sent = captor.getValue();
		// basit doğrulama
		assert sent.musicianProfileId().equals(musicianProfileId);
		assert sent.venueId().equals(venueId);
	}
	
	@Test
	void acceptRequest_ok() throws Exception {
		var accepted = new ArtistVenueConnectionRequestResponseDto(
				requestId, musicianProfileId, null, venueId, "StageNameX", null, null, "VenueX",
				"see you!", "ACCEPTED", RequestByType.ARTIST, "2025-01-01T12:00:00Z"
		);
		when(service.acceptRequest(eq(actingUserId), eq(requestId))).thenReturn(accepted);
		
		mockMvc.perform(post(BASE + "/" + requestId + "/accept"))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.message").value("Basvuru basariyla onaylandi."))
		       .andExpect(jsonPath("$.data.status").value("ACCEPTED"))
		       .andExpect(jsonPath("$.data.id").value(requestId.toString()));
		
		verify(service).acceptRequest(eq(actingUserId), eq(requestId));
	}
	
	@Test
	void rejectRequest_ok() throws Exception {
		var rejected = new ArtistVenueConnectionRequestResponseDto(
				requestId, musicianProfileId, null, venueId, "StageNameX", null, null, "VenueX",
				"see you!", "REJECTED", RequestByType.ARTIST, "2025-01-01T12:00:00Z"
		);
		when(service.rejectRequest(eq(actingUserId), eq(requestId))).thenReturn(rejected);
		
		mockMvc.perform(post(BASE + "/" + requestId + "/reject"))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.message", containsString("reddedildi")))
		       .andExpect(jsonPath("$.data.status").value("REJECTED"))
		       .andExpect(jsonPath("$.data.id").value(requestId.toString()));
		
		verify(service).rejectRequest(eq(actingUserId), eq(requestId));
	}
	
	@Test
	void getRequestsByMusician_ok() throws Exception {
		var other = new ArtistVenueConnectionRequestResponseDto(
				UUID.randomUUID(), musicianProfileId, null, UUID.randomUUID(),
				"StageNameX", null, null, "VenueY",
				"msg", "PENDING", RequestByType.VENUE, "2025-01-02T10:00:00Z"
		);
		
		when(service.getRequestByMusicianProfile(eq(actingUserId), eq(musicianProfileId), eq(RequestStatus.PENDING)))
				.thenReturn(List.of(sample, other));
		
		mockMvc.perform(get(BASE + "/musician/" + musicianProfileId)
				                .param("status", "PENDING"))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.data", hasSize(2)))
		       .andExpect(jsonPath("$.data[0].musicianProfileId").value(musicianProfileId.toString()))
		       .andExpect(jsonPath("$.data[1].musicianProfileId").value(musicianProfileId.toString()));
		
		verify(service).getRequestByMusicianProfile(eq(actingUserId), eq(musicianProfileId), eq(RequestStatus.PENDING));
	}
	
	@Test
	void getRequestsByVenue_ok() throws Exception {
		var other = new ArtistVenueConnectionRequestResponseDto(
				UUID.randomUUID(), UUID.randomUUID(), null, venueId,
				"StageNameZ", null, null, "VenueX",
				"msg2", "PENDING", RequestByType.ARTIST, "2025-01-03T09:00:00Z"
		);
		
		when(service.getRequestsByVenue(eq(actingUserId), eq(venueId), eq(RequestStatus.PENDING)))
				.thenReturn(List.of(sample, other));
		
		mockMvc.perform(get(BASE + "/venue/" + venueId)
				                .param("status", "PENDING"))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.data", hasSize(2)))
		       .andExpect(jsonPath("$.data[0].venueId").value(venueId.toString()))
		       .andExpect(jsonPath("$.data[1].venueId").value(venueId.toString()));
		
		verify(service).getRequestsByVenue(eq(actingUserId), eq(venueId), eq(RequestStatus.PENDING));
	}
}
