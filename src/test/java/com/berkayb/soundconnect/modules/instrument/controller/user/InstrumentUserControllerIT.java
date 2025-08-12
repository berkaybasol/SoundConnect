package com.berkayb.soundconnect.modules.instrument.controller.user;

import com.berkayb.soundconnect.modules.instrument.dto.response.InstrumentResponseDto;
import com.berkayb.soundconnect.modules.instrument.service.InstrumentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static com.berkayb.soundconnect.shared.constant.EndPoints.InstrumentEndpoints.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = InstrumentUserController.class)
@AutoConfigureMockMvc(addFilters = false)
class InstrumentUserControllerIT {
	
	@Autowired MockMvc mockMvc;
	
	@MockitoBean
	private InstrumentService instrumentService;
	
	@MockitoBean
	private com.berkayb.soundconnect.auth.security.JwtTokenProvider jwtTokenProvider;
	
	@MockitoBean
	private com.berkayb.soundconnect.auth.security.JwtAuthenticationFilter jwtAuthenticationFilter;
	
	@Test
	void getAllInstruments_shouldReturnList() throws Exception {
		var r1 = new InstrumentResponseDto(UUID.randomUUID().toString(), "Guitar");
		var r2 = new InstrumentResponseDto(UUID.randomUUID().toString(), "Bass");
		when(instrumentService.findAll()).thenReturn(List.of(r1, r2));
		
		mockMvc.perform(get(USER_BASE + LIST))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.data").isArray())
		       .andExpect(jsonPath("$.data[0].name").exists());
	}
	
	@Test
	void getInstrumentById_shouldReturnOne() throws Exception {
		var id = UUID.randomUUID();
		var resp = new InstrumentResponseDto(id.toString(), "Piano");
		when(instrumentService.findById(id)).thenReturn(resp);
		
		mockMvc.perform(get(USER_BASE + GET_BY_ID, id))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.data.id").value(id.toString()))
		       .andExpect(jsonPath("$.data.name").value("Piano"));
	}
}