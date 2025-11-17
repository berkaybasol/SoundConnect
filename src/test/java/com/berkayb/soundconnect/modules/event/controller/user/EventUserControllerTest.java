package com.berkayb.soundconnect.modules.event.controller.user;

import com.berkayb.soundconnect.modules.event.dto.response.EventResponseDto;
import com.berkayb.soundconnect.modules.event.enums.PerformerType;
import com.berkayb.soundconnect.modules.event.service.EventService;
import com.berkayb.soundconnect.shared.constant.EndPoints;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(
		controllers = EventUserController.class,
		excludeFilters = @ComponentScan.Filter(
				type = FilterType.ASSIGNABLE_TYPE,
				classes = com.berkayb.soundconnect.auth.security.JwtAuthenticationFilter.class
		)
)
@AutoConfigureMockMvc(addFilters = false)
class EventUserControllerTest {
	
	@Autowired
	private MockMvc mockMvc;
	
	@MockitoBean
	private EventService eventService;
	
	@Autowired
	private ObjectMapper objectMapper;
	
	@Test
	@DisplayName("GET /api/v1/events/today -> Günün etkinlikleri listelenmeli")
	void getTodayEvents_shouldReturnOk() throws Exception {
		var event1 = new EventResponseDto(
				UUID.randomUUID(),
				"Rockers",
				PerformerType.BAND,
				Set.of("Ali", "Veli"),
				UUID.randomUUID(),
				"IF Performance Hall",
				"Ankara",
				"Çankaya",
				"Kızılay",
				LocalDate.now(),
				LocalTime.of(21, 0),
				LocalTime.of(23, 0),
				"desc"
		);
		
		var event2 = new EventResponseDto(
				UUID.randomUUID(),
				"Solo Berkay",
				PerformerType.MUSICIAN,
				Set.of(),
				UUID.randomUUID(),
				"Jolly Joker",
				"Ankara",
				"Etimesgut",
				"Bağlıca",
				LocalDate.now(),
				LocalTime.of(20, 0),
				LocalTime.of(22, 0),
				"desc"
		);
		
		Mockito.when(eventService.getEventsByDate(LocalDate.now()))
		       .thenReturn(List.of(event1, event2));
		
		mockMvc.perform(get(EndPoints.Event.USER_BASE + EndPoints.Event.USER_TODAY)
				                .accept(MediaType.APPLICATION_JSON))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.data[0].performerName").value("Rockers"))
		       .andExpect(jsonPath("$.data[1].venueName").value("Jolly Joker"))
		       .andExpect(jsonPath("$.data.length()").value(2));
	}
}