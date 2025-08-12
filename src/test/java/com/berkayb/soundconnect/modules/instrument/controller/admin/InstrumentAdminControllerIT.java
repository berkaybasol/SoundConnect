package com.berkayb.soundconnect.modules.instrument.controller.admin;

import com.berkayb.soundconnect.modules.instrument.dto.request.InstrumentSaveRequestDto;
import com.berkayb.soundconnect.modules.instrument.dto.response.InstrumentResponseDto;
import com.berkayb.soundconnect.modules.instrument.service.InstrumentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static com.berkayb.soundconnect.shared.constant.EndPoints.InstrumentEndpoints.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = InstrumentAdminController.class)
@AutoConfigureMockMvc(addFilters = false) // security filtrelerini kapat
class InstrumentAdminControllerIT {
	
	@Autowired MockMvc mockMvc;
	@Autowired ObjectMapper om;
	
	@MockitoBean
	private InstrumentService instrumentService;
	
	@MockitoBean
	private com.berkayb.soundconnect.auth.security.JwtTokenProvider jwtTokenProvider;
	
	@MockitoBean
	private com.berkayb.soundconnect.auth.security.JwtAuthenticationFilter jwtAuthenticationFilter;
	
	@Test
	void saveInstrument_shouldReturn201() throws Exception {
		var req = new InstrumentSaveRequestDto("Saz");
		var resp = new InstrumentResponseDto(UUID.randomUUID().toString(), "Saz");
		
		when(instrumentService.save(any())).thenReturn(resp);
		
		mockMvc.perform(post(ADMIN_BASE + CREATE)
				                .contentType(MediaType.APPLICATION_JSON)
				                .content(om.writeValueAsString(req)))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.code").value(201))
		       .andExpect(jsonPath("$.data.name").value("Saz"));
	}
	
	@Test
	void deleteInstrument_shouldReturn200() throws Exception {
		var id = UUID.randomUUID();
		
		mockMvc.perform(delete(ADMIN_BASE + DELETE, id)) // DELETE = "/{id}" olmalı
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.code").value(200));
		
		verify(instrumentService).deleteById(eq(id));
	}
}