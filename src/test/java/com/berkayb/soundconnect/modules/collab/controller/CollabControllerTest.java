package com.berkayb.soundconnect.modules.collab.controller;

import com.berkayb.soundconnect.auth.security.JwtAuthenticationFilter;
import com.berkayb.soundconnect.auth.security.JwtTokenProvider;
import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.collab.dto.request.CollabCreateRequestDto;
import com.berkayb.soundconnect.modules.collab.dto.request.CollabFilterRequestDto;
import com.berkayb.soundconnect.modules.collab.dto.request.CollabUpdateRequestDto;
import com.berkayb.soundconnect.modules.collab.dto.request.RequiredSlotRequestDto;
import com.berkayb.soundconnect.modules.collab.dto.response.CollabResponseDto;
import com.berkayb.soundconnect.modules.collab.enums.CollabCategory;
import com.berkayb.soundconnect.modules.collab.enums.CollabRole;
import com.berkayb.soundconnect.modules.collab.service.CollabService;
import com.berkayb.soundconnect.shared.constant.EndPoints;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.Resource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.security.Principal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;


@WebMvcTest(controllers = CollabController.class)
@AutoConfigureMockMvc(addFilters = false)
@Tag("web")
class CollabControllerTest {
	
	static final String BASE   = EndPoints.Collab.BASE;
	static final String CREATE = EndPoints.Collab.CREATE;
	static final String UPDATE = EndPoints.Collab.UPDATE;
	static final String DELETE = EndPoints.Collab.DELETE;
	static final String BY_ID  = EndPoints.Collab.BY_ID;
	static final String SEARCH = EndPoints.Collab.SEARCH;
	
	@Resource MockMvc mockMvc;
	@Resource ObjectMapper objectMapper;
	
	@MockitoBean CollabService collabService;
	@MockitoBean JwtAuthenticationFilter jwtAuthenticationFilter;
	@MockitoBean JwtTokenProvider jwtTokenProvider;
	
	// -----------------------------
	private Principal principal(UUID userId) {
		UserDetailsImpl ud = new UserDetailsImpl(
				com.berkayb.soundconnect.modules.user.entity.User.builder()
				                                                 .id(userId)
				                                                 .username("berkay")
				                                                 .build()
		);
		return new UsernamePasswordAuthenticationToken(ud, null, ud.getAuthorities());
	}
	// -----------------------------
	
	// -----------------------------------------------------------
	@Test
	@DisplayName("POST /collab/create → ilan oluşturulmalı")
	void createCollab_ok() throws Exception {
		UUID userId = UUID.randomUUID();
		RequiredSlotRequestDto slot1 = new RequiredSlotRequestDto(UUID.randomUUID(), 2);
		
		CollabCreateRequestDto req = new CollabCreateRequestDto(
				"Gitarist Arıyoruz",
				"Test açıklama",
				CollabCategory.GIG,
				Set.of(CollabRole.MUSICIAN),
				UUID.randomUUID(),
				500,
				false,
				null,
				Set.of(slot1)
		);
		
		CollabResponseDto resp = new CollabResponseDto(
				UUID.randomUUID(),
				userId,
				CollabRole.MUSICIAN,
				Set.of(CollabRole.MUSICIAN),
				CollabCategory.GIG,
				req.title(),
				req.description(),
				req.price(),
				req.daily(),
				req.expirationTime(),
				req.cityId(),
				"Ankara",
				Set.of(),
				Set.of(slot1.instrumentId()),
				true,
				Set.of(),
				true,
				false,
				2
		);
		
		when(collabService.create(eq(userId), any())).thenReturn(resp);
		
		mockMvc.perform(
				       post(BASE + CREATE)
						       .principal(principal(userId))
						       .contentType(MediaType.APPLICATION_JSON)
						       .content(objectMapper.writeValueAsString(req))
		       )
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.data.title").value("Gitarist Arıyoruz"));
		
		ArgumentCaptor<CollabCreateRequestDto> cap = ArgumentCaptor.forClass(CollabCreateRequestDto.class);
		verify(collabService).create(eq(userId), cap.capture());
		assertThat(cap.getValue().title()).isEqualTo("Gitarist Arıyoruz");
	}
	// -----------------------------------------------------------
	
	
	// -----------------------------------------------------------
	@Test
	@DisplayName("PUT /collab/{id}/update → güncelleme yapılmalı")
	void updateCollab_ok() throws Exception {
		UUID userId = UUID.randomUUID();
		UUID collabId = UUID.randomUUID();
		RequiredSlotRequestDto slot1 = new RequiredSlotRequestDto(UUID.randomUUID(), 3);
		
		CollabUpdateRequestDto req = new CollabUpdateRequestDto(
				"Yeni Başlık",
				"Yeni açıklama",
				CollabCategory.RECORDING,
				Set.of(CollabRole.PRODUCER),
				UUID.randomUUID(),
				700,
				true,
				LocalDateTime.now().plusDays(1),
				Set.of(slot1)
		);
		
		CollabResponseDto resp = new CollabResponseDto(
				collabId,
				userId,
				CollabRole.PRODUCER,
				Set.of(CollabRole.PRODUCER),
				CollabCategory.RECORDING,
				req.title(),
				req.description(),
				req.price(),
				req.daily(),
				req.expirationTime(),
				req.cityId(),
				"İstanbul",
				Set.of(),
				Set.of(slot1.instrumentId()),
				true,
				Set.of(),
				true,
				false,
				3
		);
		
		when(collabService.update(eq(collabId), eq(userId), any())).thenReturn(resp);
		
		mockMvc.perform(
				       put(BASE + UPDATE, collabId)
						       .principal(principal(userId))
						       .contentType(MediaType.APPLICATION_JSON)
						       .content(objectMapper.writeValueAsString(req))
		       )
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.data.title").value("Yeni Başlık"))
		       .andExpect(jsonPath("$.data.category").value("RECORDING"));
		
		ArgumentCaptor<CollabUpdateRequestDto> cap = ArgumentCaptor.forClass(CollabUpdateRequestDto.class);
		verify(collabService).update(eq(collabId), eq(userId), cap.capture());
		assertThat(cap.getValue().title()).isEqualTo("Yeni Başlık");
	}
	// -----------------------------------------------------------
	
	
	// -----------------------------------------------------------
	@Test
	@DisplayName("DELETE /collab/{id} → ilan silinmeli")
	void deleteCollab_ok() throws Exception {
		UUID userId = UUID.randomUUID();
		UUID collabId = UUID.randomUUID();
		
		doNothing().when(collabService).delete(collabId, userId);
		
		mockMvc.perform(
				       delete(BASE + DELETE, collabId)
						       .principal(principal(userId))
		       )
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true));
		       
		
		verify(collabService).delete(collabId, userId);
	}
	// -----------------------------------------------------------
	
	
	// -----------------------------------------------------------
	@Test
	@DisplayName("GET /collab/{id} → ilan getirilmeli (auth optional)")
	void getById_ok() throws Exception {
		UUID collabId = UUID.randomUUID();
		
		CollabResponseDto resp = new CollabResponseDto(
				collabId,
				UUID.randomUUID(),
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
				false,
				false,
				2
		);
		
		when(collabService.getById(eq(collabId), isNull())).thenReturn(resp);
		
		mockMvc.perform(get(BASE + BY_ID, collabId))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.data.title").value("Başlık"));
		
		verify(collabService).getById(eq(collabId), isNull());
	}
	// -----------------------------------------------------------
	
	
	// -----------------------------------------------------------
	@Test
	@DisplayName("GET /collab/search → filtre + pageable çalışmalı")
	void search_ok() throws Exception {
		UUID cityId = UUID.randomUUID();
		
		CollabFilterRequestDto filter = new CollabFilterRequestDto(
				cityId,
				CollabCategory.GIG,
				null,
				null,
				null,
				null,
				null,
				null,
				null
		);
		
		CollabResponseDto resp1 = new CollabResponseDto(
				UUID.randomUUID(), UUID.randomUUID(),
				CollabRole.MUSICIAN, Set.of(CollabRole.MUSICIAN),
				CollabCategory.GIG,
				"İlan 1", "desc", 200, false, null,
				cityId, "İstanbul",
				Set.of(), Set.of(),
				true, Set.of(), false, false, 1
		);
		
		Page<CollabResponseDto> page = new PageImpl<>(
				List.of(resp1),
				PageRequest.of(0, 10),
				1
		);
		
		when(collabService.search(isNull(), any(), any())).thenReturn(page);
		
		mockMvc.perform(
				       get(BASE + SEARCH)
						       .param("cityId", cityId.toString())
						       .param("category", "GIG")
						       .param("page", "0")
						       .param("size", "10")
		       )
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.data.content[0].title").value("İlan 1"));
		
		verify(collabService).search(isNull(), any(), any());
	}
	// -----------------------------------------------------------
	
}