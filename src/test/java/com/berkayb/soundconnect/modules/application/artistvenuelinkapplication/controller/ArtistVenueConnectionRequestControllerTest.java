package com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.controller;

import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.dto.request.ArtistVenueConnectionRequestCreateDto;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.dto.response.ArtistVenueConnectionRequestResponseDto;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.enums.RequestByType;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.service.ArtistVenueConnectionRequestService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
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
	ArtistVenueConnectionRequestResponseDto sample;
	
	@BeforeEach
	void setUp() {
		requestId = UUID.randomUUID();
		musicianProfileId = UUID.randomUUID();
		venueId = UUID.randomUUID();
		
		sample = new ArtistVenueConnectionRequestResponseDto(
				requestId,
				musicianProfileId,
				venueId,
				"StageNameX",
				"VenueX",
				"see you!",
				"PENDING",
				RequestByType.ARTIST,
				"2025-01-01T12:00:00Z"
		);
	}
	
	@Test
	void createRequest_ok() throws Exception {
		when(service.createRequest(any(), eq(RequestByType.ARTIST))).thenReturn(sample);
		
		var body = new ArtistVenueConnectionRequestCreateDto(musicianProfileId, venueId, "see you!");
		mockMvc.perform(post(BASE + "/request")
				                .param("requestByType", "ARTIST")
				                .contentType(MediaType.APPLICATION_JSON)
				                .content(om.writeValueAsBytes(body)))
		       .andExpect(status().isOk())
		       .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.message", containsString("Başvuru başarıyla oluşturuldu")))
		       .andExpect(jsonPath("$.data.id").value(requestId.toString()))
		       .andExpect(jsonPath("$.data.musicianProfileId").value(musicianProfileId.toString()))
		       .andExpect(jsonPath("$.data.venueId").value(venueId.toString()))
		       .andExpect(jsonPath("$.data.status").value("PENDING"))
		       .andExpect(jsonPath("$.data.requestByType").value("ARTIST"));
		
		// service'e doğru argüman gitti mi?
		ArgumentCaptor<ArtistVenueConnectionRequestCreateDto> captor = ArgumentCaptor.forClass(ArtistVenueConnectionRequestCreateDto.class);
		verify(service).createRequest(captor.capture(), eq(RequestByType.ARTIST));
		var sent = captor.getValue();
		// basit doğrulama
		assert sent.musicianProfileId().equals(musicianProfileId);
		assert sent.venueId().equals(venueId);
	}
	
	@Test
	void acceptRequest_ok() throws Exception {
		var accepted = new ArtistVenueConnectionRequestResponseDto(
				requestId, musicianProfileId, venueId, "StageNameX", "VenueX",
				"see you!", "ACCEPTED", RequestByType.ARTIST, "2025-01-01T12:00:00Z"
		);
		when(service.acceptRequest(requestId)).thenReturn(accepted);
		
		mockMvc.perform(post(BASE + "/" + requestId + "/accept"))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.message", containsString("onaylandı")))
		       .andExpect(jsonPath("$.data.status").value("ACCEPTED"))
		       .andExpect(jsonPath("$.data.id").value(requestId.toString()));
		
		verify(service).acceptRequest(requestId);
	}
	
	@Test
	void rejectRequest_ok() throws Exception {
		var rejected = new ArtistVenueConnectionRequestResponseDto(
				requestId, musicianProfileId, venueId, "StageNameX", "VenueX",
				"see you!", "REJECTED", RequestByType.ARTIST, "2025-01-01T12:00:00Z"
		);
		when(service.rejectRequest(requestId)).thenReturn(rejected);
		
		mockMvc.perform(post(BASE + "/" + requestId + "/reject"))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.message", containsString("reddedildi")))
		       .andExpect(jsonPath("$.data.status").value("REJECTED"))
		       .andExpect(jsonPath("$.data.id").value(requestId.toString()));
		
		verify(service).rejectRequest(requestId);
	}
	
	@Test
	void getRequestsByMusician_ok() throws Exception {
		var other = new ArtistVenueConnectionRequestResponseDto(
				UUID.randomUUID(), musicianProfileId, UUID.randomUUID(),
				"StageNameX", "VenueY",
				"msg", "PENDING", RequestByType.VENUE, "2025-01-02T10:00:00Z"
		);
		
		when(service.getRequestByMusicianProfile(musicianProfileId)).thenReturn(List.of(sample, other));
		
		mockMvc.perform(get(BASE + "/musician/" + musicianProfileId))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.data", hasSize(2)))
		       .andExpect(jsonPath("$.data[0].musicianProfileId").value(musicianProfileId.toString()))
		       .andExpect(jsonPath("$.data[1].musicianProfileId").value(musicianProfileId.toString()));
		
		verify(service).getRequestByMusicianProfile(musicianProfileId);
	}
	
	@Test
	void getRequestsByVenue_ok() throws Exception {
		var other = new ArtistVenueConnectionRequestResponseDto(
				UUID.randomUUID(), UUID.randomUUID(), venueId,
				"StageNameZ", "VenueX",
				"msg2", "PENDING", RequestByType.ARTIST, "2025-01-03T09:00:00Z"
		);
		
		when(service.getRequestsByVenue(venueId)).thenReturn(List.of(sample, other));
		
		mockMvc.perform(get(BASE + "/venue/" + venueId))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.data", hasSize(2)))
		       .andExpect(jsonPath("$.data[0].venueId").value(venueId.toString()))
		       .andExpect(jsonPath("$.data[1].venueId").value(venueId.toString()));
		
		verify(service).getRequestsByVenue(venueId);
	}
}