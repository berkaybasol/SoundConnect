package com.berkayb.soundconnect.modules.event.controller.admin;
import com.berkayb.soundconnect.modules.event.dto.request.EventCreateRequestDto;
import com.berkayb.soundconnect.modules.event.dto.response.EventResponseDto;
import com.berkayb.soundconnect.modules.event.service.EventService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.berkayb.soundconnect.shared.constant.EndPoints.Event.*;


@RestController
@RequestMapping(ADMIN_BASE)
@RequiredArgsConstructor
@Slf4j
public class EventAdminController {
	
	private final EventService eventService;
	
	@PostMapping(CREATE)
	@Operation(summary = "Yeni event olustur")
	public ResponseEntity<BaseResponse<EventResponseDto>> createEvent(@RequestParam UUID createdByUserId, @Valid @RequestBody EventCreateRequestDto dto) {
		EventResponseDto response = eventService.createEvent(createdByUserId, dto);
		return ResponseEntity.ok(BaseResponse.<EventResponseDto>builder()
				                         .success(true)
				                         .message("Event created succesfully")
				                         .code(200)
				                         .data(response)
				                         .build());
	}
	
	
	@DeleteMapping(BY_ID)
	@Operation(summary = "Event sil")
	public ResponseEntity<BaseResponse<Void>> deleteEvent(@PathVariable UUID eventId) {
		eventService.deleteEventById(eventId);
		
		return ResponseEntity.ok(
				BaseResponse.<Void>builder()
				            .success(true)
				            .message("Event deleted succesfully")
				            .code(200)
				            .build());
	}
	
	
	
	
	
}