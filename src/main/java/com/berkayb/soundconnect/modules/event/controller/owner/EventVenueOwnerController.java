package com.berkayb.soundconnect.modules.event.controller.owner;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.event.dto.request.EventCreateRequestDto;
import com.berkayb.soundconnect.modules.event.dto.response.EventResponseDto;
import com.berkayb.soundconnect.modules.event.service.EventService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List; //eklendi
import java.util.UUID;

import static com.berkayb.soundconnect.shared.constant.EndPoints.Event.*;

@RestController
@RequestMapping(OWNER_BASE)
@RequiredArgsConstructor
@Tag(name = "FOR VENUE OWNER / Events", description = "Venue owner kendi mekanina ait eventleri olusturur ve yonetir")
public class EventVenueOwnerController {
	
	private final EventService eventService;
	
	@PreAuthorize("hasRole('VENUE')")
	@PostMapping(CREATE)
	@Operation(summary = "Venue owner kendi mekani icin yeni event olusturur")
	public ResponseEntity<BaseResponse<EventResponseDto>> createEvent(
			@AuthenticationPrincipal UserDetailsImpl userDetails,
			@Valid @RequestBody EventCreateRequestDto dto) {
		
		EventResponseDto response = eventService.createEvent(userDetails.getUser().getId(), dto);
		
		return ResponseEntity.ok(
				BaseResponse.<EventResponseDto>builder()
				            .success(true)
				            .message("Event created successfully")
				            .code(200)
				            .data(response)
				            .build()
		);
	}
	
	@PreAuthorize("hasRole('VENUE')")
	@GetMapping("/venue/{venueId}")
	@Operation(summary = "Venue owner kendi mekanina ait eventleri listeler")
	public ResponseEntity<BaseResponse<List<EventResponseDto>>> getOwnerVenueEvents(
	                                                                                 @AuthenticationPrincipal UserDetailsImpl userDetails,
	                                                                                 @PathVariable UUID venueId) {
		
		List<EventResponseDto> response = eventService.getOwnerEventsByVenue(userDetails.getUser().getId(), venueId);
		
		return ResponseEntity.ok(BaseResponse.<List<EventResponseDto>>builder()
		                                     .success(true)
		                                     .code(200)
		                                     .message("Venue owner eventleri listelendi")
		                                     .data(response)
		                                     .build());
	}
	
	@PreAuthorize("hasRole('VENUE')")
	@DeleteMapping(DELETE)
	@Operation(summary = "Venue owner kendi mekanina ait eventi siler")
	public ResponseEntity<BaseResponse<Void>> deleteEvent(
			@AuthenticationPrincipal UserDetailsImpl userDetails,
			@PathVariable UUID eventId) {
		
		eventService.deleteEventById(userDetails.getUser().getId(), eventId);
		
		return ResponseEntity.ok(
				BaseResponse.<Void>builder()
				            .success(true)
				            .message("Event deleted successfully")
				            .code(200)
				            .build()
		);
	}
}
