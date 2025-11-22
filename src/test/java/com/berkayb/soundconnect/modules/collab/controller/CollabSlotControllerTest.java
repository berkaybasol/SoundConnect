package com.berkayb.soundconnect.modules.collab.controller;

import com.berkayb.soundconnect.auth.security.JwtAuthenticationFilter;
import com.berkayb.soundconnect.auth.security.JwtTokenProvider;
import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.collab.dto.request.CollabFillSlotRequestDto;
import com.berkayb.soundconnect.modules.collab.dto.request.CollabUnfillSlotRequestDto;
import com.berkayb.soundconnect.modules.collab.dto.response.CollabResponseDto;
import com.berkayb.soundconnect.modules.collab.enums.CollabCategory;
import com.berkayb.soundconnect.modules.collab.enums.CollabRole;
import com.berkayb.soundconnect.modules.collab.service.CollabSlotManagementService;
import com.berkayb.soundconnect.shared.constant.EndPoints;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;

import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.security.Principal;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = CollabSlotController.class)
@AutoConfigureMockMvc(addFilters = false)
@Tag("web")
class CollabSlotControllerTest {
	
	static final String BASE      = EndPoints.Collab.BASE;
	static final String FILL      = EndPoints.Collab.FILL_SLOT;
	static final String UNFILL    = EndPoints.Collab.UNFILL_SLOT;
	
	@Resource MockMvc mockMvc;
	@Resource ObjectMapper objectMapper;
	
	@MockitoBean CollabSlotManagementService slotService;
	@MockitoBean JwtAuthenticationFilter jwtAuthenticationFilter;
	@MockitoBean JwtTokenProvider jwtTokenProvider;
	
	private Principal principal(UUID userId) {
		UserDetailsImpl ud = new UserDetailsImpl(
				com.berkayb.soundconnect.modules.user.entity.User.builder()
				                                                 .id(userId)
				                                                 .username("berkay")
				                                                 .build()
		);
		return new UsernamePasswordAuthenticationToken(ud, null, ud.getAuthorities());
	}
	
	
	// -------------------------------------------------------------------------
	@Test
	@DisplayName("POST /collab/{id}/fill-slot → slot doldurulmalı")
	void fillSlot_ok() throws Exception {
		UUID userId   = UUID.randomUUID();
		UUID collabId = UUID.randomUUID();
		UUID instId   = UUID.randomUUID();
		
		CollabFillSlotRequestDto req = new CollabFillSlotRequestDto(instId);
		
		CollabResponseDto resp = new CollabResponseDto(
				collabId,
				userId,
				CollabRole.MUSICIAN,
				Set.of(CollabRole.MUSICIAN),
				CollabCategory.GIG,
				"Başlık",
				"Açıklama",
				100,
				false,
				null,
				UUID.randomUUID(),
				"Ankara",
				Set.of(instId),
				Set.of(instId),
				true,
				Set.of(),
				true,
				false,
				5
		);
		
		when(slotService.fill(eq(userId), eq(collabId), any())).thenReturn(resp);
		
		mockMvc.perform(
				       post(BASE + FILL, collabId)
						       .principal(principal(userId))
						       .contentType(MediaType.APPLICATION_JSON)
						       .content(objectMapper.writeValueAsString(req))
		       )
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.data.id").value(collabId.toString()))
		       .andExpect(jsonPath("$.data.filledInstrumentIds[0]").value(instId.toString()));
		
		ArgumentCaptor<CollabFillSlotRequestDto> captor =
				ArgumentCaptor.forClass(CollabFillSlotRequestDto.class);
		
		verify(slotService).fill(eq(userId), eq(collabId), captor.capture());
		assertThat(captor.getValue().instrumentId()).isEqualTo(instId);
	}
	// -------------------------------------------------------------------------
	
	
	// -------------------------------------------------------------------------
	@Test
	@DisplayName("POST /collab/{id}/unfill-slot → slot boşaltılmalı")
	void unfillSlot_ok() throws Exception {
		UUID userId   = UUID.randomUUID();
		UUID collabId = UUID.randomUUID();
		UUID instId   = UUID.randomUUID();
		
		CollabUnfillSlotRequestDto req = new CollabUnfillSlotRequestDto(instId);
		
		CollabResponseDto resp = new CollabResponseDto(
				collabId,
				userId,
				CollabRole.MUSICIAN,
				Set.of(CollabRole.MUSICIAN),
				CollabCategory.GIG,
				"Başlık",
				"Açıklama",
				100,
				false,
				null,
				UUID.randomUUID(),
				"Ankara",
				Set.of(),
				Set.of(),
				true,
				Set.of(),
				true,
				false,
				5
		);
		
		when(slotService.unfill(eq(userId), eq(collabId), any())).thenReturn(resp);
		
		mockMvc.perform(
				       post(BASE + UNFILL, collabId)
						       .principal(principal(userId))
						       .contentType(MediaType.APPLICATION_JSON)
						       .content(objectMapper.writeValueAsString(req))
		       )
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.data.id").value(collabId.toString()));
		
		ArgumentCaptor<CollabUnfillSlotRequestDto> captor =
				ArgumentCaptor.forClass(CollabUnfillSlotRequestDto.class);
		
		verify(slotService).unfill(eq(userId), eq(collabId), captor.capture());
		assertThat(captor.getValue().instrumentId()).isEqualTo(instId);
	}
	// -------------------------------------------------------------------------
	
}