package com.berkayb.soundconnect.modules.venue.controller.admin;

import com.berkayb.soundconnect.shared.response.BaseResponse;
import com.berkayb.soundconnect.modules.venue.dto.request.VenueRequestDto;
import com.berkayb.soundconnect.modules.venue.dto.response.VenueResponseDto;
import com.berkayb.soundconnect.modules.venue.service.VenueService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import static com.berkayb.soundconnect.shared.constant.EndPoints.Venue.*;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping(BASE)
@RequiredArgsConstructor
@Tag(name = "FOR ADMIN / Venue", description = "Admin venue transtactions")
public class VenueControllerImpl implements VenueController {
	
	private final VenueService venueService;
	
	//TODO @PreAuthorize("hasAuthority('WRITE_VENUE')")
	@PostMapping(SAVE)
	@Override
	public ResponseEntity<BaseResponse<VenueResponseDto>> save(@RequestBody @Valid VenueRequestDto dto) {
		log.info("Admin adds new venue: {}", dto.name());
		VenueResponseDto saved = venueService.save(dto);
		return ResponseEntity.ok(BaseResponse.<VenueResponseDto>builder()
		                                     .success(true)
		                                     .message("Venue saved successfully")
		                                     .code(201)
		                                     .data(saved)
		                                     .build());
	}
	
	//TODO @PreAuthorize("hasAuthority('WRITE_VENUE')")
	@PutMapping(UPDATE)
	@Override
	public ResponseEntity<BaseResponse<VenueResponseDto>> update(@PathVariable UUID id, @RequestBody @Valid VenueRequestDto dto) {
		log.info("Admin updates venue id: {}", id);
		VenueResponseDto updated = venueService.update(id, dto);
		return ResponseEntity.ok(BaseResponse.<VenueResponseDto>builder()
		                                     .success(true)
		                                     .message("Venue updated successfully")
		                                     .code(200)
		                                     .data(updated)
		                                     .build());
	}
	
	//TODO @PreAuthorize("hasAuthority('READ_VENUE')")
	@GetMapping(GET_ALL)
	@Override
	public ResponseEntity<BaseResponse<List<VenueResponseDto>>> findAll() {
		log.info("Admin fetches all venues");
		List<VenueResponseDto> venues = venueService.findAll();
		return ResponseEntity.ok(BaseResponse.<List<VenueResponseDto>>builder()
		                                     .success(true)
		                                     .message("All venues retrieved successfully")
		                                     .code(200)
		                                     .data(venues)
		                                     .build());
	}
	
	//TODO @PreAuthorize("hasAuthority('READ_VENUE')")
	@GetMapping(GET_BY_ID)
	@Override
	public ResponseEntity<BaseResponse<VenueResponseDto>> findById(@PathVariable UUID id) {
		log.info("Admin fetches venue by id: {}", id);
		VenueResponseDto venue = venueService.findById(id);
		return ResponseEntity.ok(BaseResponse.<VenueResponseDto>builder()
		                                     .success(true)
		                                     .message("Venue found")
		                                     .code(200)
		                                     .data(venue)
		                                     .build());
	}
	
	//TODO @PreAuthorize("hasAuthority('DELETE_VENUE')")
	@DeleteMapping(DELETE)
	@Override
	public ResponseEntity<BaseResponse<Void>> delete(@PathVariable UUID id) {
		log.info("Admin deletes venue with id: {}", id);
		venueService.delete(id);
		return ResponseEntity.ok(BaseResponse.<Void>builder()
		                                     .success(true)
		                                     .message("Venue deleted successfully")
		                                     .code(200)
		                                     .build());
	}
}