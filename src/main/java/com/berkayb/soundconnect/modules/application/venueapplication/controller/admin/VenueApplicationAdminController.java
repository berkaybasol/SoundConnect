package com.berkayb.soundconnect.modules.application.venueapplication.controller.admin;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.application.venueapplication.dto.response.VenueApplicationResponseDto;
import com.berkayb.soundconnect.modules.application.venueapplication.enums.ApplicationStatus;
import com.berkayb.soundconnect.modules.application.venueapplication.service.VenueApplicationService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

import static com.berkayb.soundconnect.shared.constant.EndPoints.VenueApplication.*;

@RestController
@RequestMapping(ADMIN_BASE)
@RequiredArgsConstructor
@Slf4j
@Tag(name = "FOR ADMIN / Venue Application", description = "Venue applications (admin operations)")
public class VenueApplicationAdminController {
	
	private final VenueApplicationService venueApplicationService;
	
	@PostMapping(APPROVE)
	public ResponseEntity<BaseResponse<VenueApplicationResponseDto>> approveVenueApplication(
			@PathVariable UUID applicationId,
			Authentication authentication) {
		
		UserDetailsImpl adminDetails = authToDetails(authentication);
		UUID adminId = adminDetails != null ? adminDetails.getUser().getId() : null;
		
		log.info("Admin {} approves venue application {}",
		         adminDetails != null ? adminDetails.getUsername() : "unknown", applicationId);
		
		var response = venueApplicationService.approveApplication(applicationId, adminId);
		
		return ResponseEntity.ok(BaseResponse.<VenueApplicationResponseDto>builder()
		                                     .success(true)
		                                     .message("Basvuru onaylandı")
		                                     .code(200)
		                                     .data(response)
		                                     .build());
	}
	
	@PostMapping(REJECT)
	public ResponseEntity<BaseResponse<VenueApplicationResponseDto>> rejectVenueApplication(
			@PathVariable UUID applicationId,
			@RequestParam String reason,
			Authentication authentication) {
		
		UserDetailsImpl adminDetails = authToDetails(authentication);
		UUID adminId = adminDetails != null ? adminDetails.getUser().getId() : null;
		
		log.info("Admin {} rejects venue application {} (reason: {})",
		         adminDetails != null ? adminDetails.getUsername() : "unknown", applicationId, reason);
		
		var response = venueApplicationService.rejectApplication(applicationId, adminId, reason);
		
		return ResponseEntity.ok(BaseResponse.<VenueApplicationResponseDto>builder()
		                                     .success(true)
		                                     .message("Basvuru reddedildi")
		                                     .code(200)
		                                     .data(response)
		                                     .build());
	}
	
	@GetMapping(BY_STATUS)
	public ResponseEntity<BaseResponse<List<VenueApplicationResponseDto>>> getApplicationByStatus(
			@RequestParam ApplicationStatus status) {
		
		log.info("Admin requests venue applications by status: {}", status);
		var list = venueApplicationService.getApplicationsByStatus(status);
		
		return ResponseEntity.ok(BaseResponse.<List<VenueApplicationResponseDto>>builder()
		                                     .success(true)
		                                     .message("basvurular listelendi")
		                                     .code(200)
		                                     .data(list)
		                                     .build());
	}
	
	@GetMapping(GET_BY_ID)
	public ResponseEntity<BaseResponse<VenueApplicationResponseDto>> getVenueApplicationById(@PathVariable UUID id) {
		log.info("Admin requests venue application by id: {}", id);
		var response = venueApplicationService.getById(id);
		
		return ResponseEntity.ok(BaseResponse.<VenueApplicationResponseDto>builder()
		                                     .success(true)
		                                     .message("Basvuru getirildi")
		                                     .code(200)
		                                     .data(response)
		                                     .build());
	}
	
	private UserDetailsImpl authToDetails(Authentication authentication) {
		if (authentication == null) return null;
		Object p = authentication.getPrincipal();
		return (p instanceof UserDetailsImpl) ? (UserDetailsImpl) p : null;
	}
}