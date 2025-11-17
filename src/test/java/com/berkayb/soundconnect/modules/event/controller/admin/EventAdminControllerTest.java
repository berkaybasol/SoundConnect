package com.berkayb.soundconnect.modules.event.controller.admin;

import com.berkayb.soundconnect.modules.event.dto.request.EventCreateRequestDto;
import com.berkayb.soundconnect.modules.event.dto.response.EventResponseDto;
import com.berkayb.soundconnect.modules.event.enums.PerformerType;
import com.berkayb.soundconnect.modules.event.service.EventService;
import com.berkayb.soundconnect.shared.constant.EndPoints;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(
		controllers = EventAdminController.class,
		excludeFilters = @ComponentScan.Filter(
				type = FilterType.ASSIGNABLE_TYPE,
				classes = com.berkayb.soundconnect.auth.security.JwtAuthenticationFilter.class
		)
)
@AutoConfigureMockMvc(addFilters = false)
class EventAdminControllerTest {
	
	@Autowired
	private MockMvc mockMvc;
	
	@MockitoBean
	private EventService eventService;
	
	@Autowired
	private ObjectMapper objectMapper;
	
	@Test
	@DisplayName("POST /api/v1/admin/events/create -> Başarılı event oluşturma")
	void createEvent_shouldReturnOk() throws Exception {
		UUID userId = UUID.randomUUID();
		UUID venueId = UUID.randomUUID();
		UUID musicianId = UUID.randomUUID();
		
		// Request DTO
		var dto = new EventCreateRequestDto(
				"Rock Night",
				"Güzel bir konser",
				LocalDate.of(2025, 11, 17),
				LocalTime.of(20, 0),
				LocalTime.of(23, 0),
				"poster.jpg",
				venueId,
				musicianId,
				null
		);
		
		// Mock Response
		var responseDto = new EventResponseDto(
				UUID.randomUUID(),
				"Berkay Başol",
				PerformerType.MUSICIAN,
				Set.of(),
				venueId,
				"IF Performance Hall",
				"Ankara",
				"Çankaya",
				"Kızılay",
				dto.eventDate(),
				dto.startTime(),
				dto.endTime(),
				dto.description()
		);
		
		Mockito.when(eventService.createEvent(Mockito.eq(userId), Mockito.any()))
		       .thenReturn(responseDto);
		
		mockMvc.perform(post(EndPoints.Event.ADMIN_BASE + EndPoints.Event.CREATE)
				                .param("createdByUserId", userId.toString())
				                .contentType(MediaType.APPLICATION_JSON)
				                .content(objectMapper.writeValueAsString(dto)))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.data.performerName").value("Berkay Başol"))
		       .andExpect(jsonPath("$.message").value("Event created succesfully"))
		       .andExpect(jsonPath("$.code").value(200));
	}
	
	@Test
	@DisplayName("POST /api/v1/admin/events/create -> Hatalı performer seçimi (hem band hem müzisyen dolu)")
	void createEvent_shouldReturnBadRequest_whenInvalidPerformer() throws Exception {
		UUID userId = UUID.randomUUID();
		UUID venueId = UUID.randomUUID();
		
		var dto = new EventCreateRequestDto(
				"Concert",
				"desc",
				LocalDate.now(),
				LocalTime.of(21, 0),
				LocalTime.of(23, 0),
				"poster.jpg",
				venueId,
				UUID.randomUUID(),
				UUID.randomUUID()
		);
		
		Mockito.when(eventService.createEvent(Mockito.eq(userId), Mockito.any()))
		       .thenThrow(new SoundConnectException(ErrorType.INVALID_PERFORMER_SELECTION));
		
		mockMvc.perform(post(EndPoints.Event.ADMIN_BASE + EndPoints.Event.CREATE)
				                .param("createdByUserId", userId.toString())
				                .contentType(MediaType.APPLICATION_JSON)
				                .content(objectMapper.writeValueAsString(dto)))
		       .andExpect(status().isBadRequest())
		       .andExpect(jsonPath("$.code").value(9210))
		       .andExpect(jsonPath("$.message").value("Invalid performer selection"))
		       .andExpect(jsonPath("$.httpStatus").value("BAD_REQUEST"))
		       .andExpect(jsonPath("$.path").value("/api/v1/admin/events/create"));
	}
}