package com.berkayb.soundconnect.modules.event.controller.owner;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.event.dto.request.EventCreateRequestDto;
import com.berkayb.soundconnect.modules.event.dto.response.EventResponseDto;
import com.berkayb.soundconnect.modules.event.enums.PerformerType;
import com.berkayb.soundconnect.modules.event.service.EventService;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.shared.constant.EndPoints;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
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
		controllers = EventVenueOwnerController.class,
		excludeFilters = @ComponentScan.Filter(
				type = FilterType.ASSIGNABLE_TYPE,
				classes = com.berkayb.soundconnect.auth.security.JwtAuthenticationFilter.class
		)
)
@AutoConfigureMockMvc(addFilters = false)
class EventVenueOwnerControllerTest {
	
	@Autowired
	private MockMvc mockMvc;
	
	@MockitoBean
	private EventService eventService;
	
	@Autowired
	private ObjectMapper objectMapper;
	
	@Test
	@DisplayName("POST /api/v1/venue-owner/events -> Başarılı event oluşturma")
	void createEvent_shouldReturnOk() throws Exception {
		UUID userId = UUID.randomUUID();
		UUID venueId = UUID.randomUUID();
		UUID musicianId = UUID.randomUUID();
		authenticateVenueOwner(userId);
		
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
				null,
				null
		);
		
		// Mock Response
		var responseDto = new EventResponseDto(
				UUID.randomUUID(),
				"Rock Night",
				"poster.jpg",
				"Berkay Başol",
				musicianId,
				null,
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
				dto.description(),
				"http://localhost/events/1"
		);
		
		Mockito.when(eventService.createEvent(Mockito.eq(userId), Mockito.any()))
		       .thenReturn(responseDto);
		
		mockMvc.perform(post(EndPoints.Event.OWNER_BASE + EndPoints.Event.CREATE)
				                .contentType(MediaType.APPLICATION_JSON)
				                .content(objectMapper.writeValueAsString(dto)))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.data.performerName").value("Berkay Başol"))
		       .andExpect(jsonPath("$.message").value("Event created successfully"))
		       .andExpect(jsonPath("$.code").value(200));
	}
	
	@Test
	@DisplayName("POST /api/v1/venue-owner/events -> Hatalı performer seçimi (hem band hem müzisyen dolu)")
	void createEvent_shouldReturnBadRequest_whenInvalidPerformer() throws Exception {
		UUID userId = UUID.randomUUID();
		UUID venueId = UUID.randomUUID();
		authenticateVenueOwner(userId);
		
		var dto = new EventCreateRequestDto(
				"Concert",
				"desc",
				LocalDate.now(),
				LocalTime.of(21, 0),
				LocalTime.of(23, 0),
				"poster.jpg",
				venueId,
				UUID.randomUUID(),
				UUID.randomUUID(),
				null
		);
		
		Mockito.when(eventService.createEvent(Mockito.eq(userId), Mockito.any()))
		       .thenThrow(new SoundConnectException(ErrorType.INVALID_PERFORMER_SELECTION));
		
		mockMvc.perform(post(EndPoints.Event.OWNER_BASE + EndPoints.Event.CREATE)
				                .contentType(MediaType.APPLICATION_JSON)
				                .content(objectMapper.writeValueAsString(dto)))
		       .andExpect(status().isBadRequest())
		       .andExpect(jsonPath("$.code").value(9210))
		       .andExpect(jsonPath("$.message").value("Invalid performer selection"))
		       .andExpect(jsonPath("$.httpStatus").value("BAD_REQUEST"))
		       .andExpect(jsonPath("$.path").value(EndPoints.Event.OWNER_BASE));
	}

	@Test
	@DisplayName("POST /api/v1/venue-owner/events -> Manuel sanatçı adı sınırı API katmanında doğrulanır")
	void createEvent_shouldReturnBadRequest_whenManualPerformerNameIsTooLong() throws Exception {
		UUID userId = UUID.randomUUID();
		authenticateVenueOwner(userId);
		var dto = new EventCreateRequestDto(
				"Concert",
				"desc",
				LocalDate.now(),
				LocalTime.of(21, 0),
				null,
				null,
				UUID.randomUUID(),
				null,
				null,
				"x".repeat(121)
		);

		mockMvc.perform(post(EndPoints.Event.OWNER_BASE + EndPoints.Event.CREATE)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(dto)))
				.andExpect(status().isBadRequest());
		Mockito.verifyNoInteractions(eventService);
	}

	@Test
	@DisplayName("POST /api/v1/venue-owner/events -> Persisted text column limits are validated before service invocation")
	void createEvent_shouldReturnBadRequest_whenPersistedTextExceedsColumnLimits() throws Exception {
		UUID userId = UUID.randomUUID();
		UUID venueId = UUID.randomUUID();
		authenticateVenueOwner(userId);
		List<EventCreateRequestDto> invalidRequests = List.of(
				new EventCreateRequestDto(
						"x".repeat(256), null, LocalDate.now(), LocalTime.NOON,
						null, null, venueId, null, null, null
				),
				new EventCreateRequestDto(
						"Concert", "x".repeat(501), LocalDate.now(), LocalTime.NOON,
						null, null, venueId, null, null, null
				),
				new EventCreateRequestDto(
						"Concert", null, LocalDate.now(), LocalTime.NOON,
						null, "x".repeat(256), venueId, null, null, null
				)
		);

		for (EventCreateRequestDto request : invalidRequests) {
			mockMvc.perform(post(EndPoints.Event.OWNER_BASE + EndPoints.Event.CREATE)
							.contentType(MediaType.APPLICATION_JSON)
							.content(objectMapper.writeValueAsString(request)))
					.andExpect(status().isBadRequest());
		}
		Mockito.verifyNoInteractions(eventService);
	}

	private void authenticateVenueOwner(UUID userId) {
		User user = User.builder()
				.id(userId)
				.username("venue-owner")
				.password("unused")
				.email("owner@example.com")
				.emailVerified(true)
				.status(UserStatus.ACTIVE)
				.roles(Set.of(Role.builder().name("ROLE_VENUE").build()))
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
}
