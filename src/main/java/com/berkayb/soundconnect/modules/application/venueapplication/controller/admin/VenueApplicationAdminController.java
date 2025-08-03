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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

import static com.berkayb.soundconnect.shared.constant.EndPoints.VenueApplication.*;

@RestController
@RequestMapping(ADMIN_BASE)
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Admin / Venue Application", description = "Venue applications (admin operations)")
public class VenueApplicationAdminController {
	private final VenueApplicationService venueApplicationService;
	
	@PostMapping(APPROVE)
	public ResponseEntity<BaseResponse<VenueApplicationResponseDto>> approveVenueApplication(@PathVariable UUID applicationId, @AuthenticationPrincipal UserDetailsImpl adminDetails) {
		log.info("Admin {} approves venue application {}", adminDetails.getUsername(), applicationId );
		var response = venueApplicationService.approveApplication(applicationId, adminDetails.getUser().getId());
		return ResponseEntity.ok(BaseResponse.<VenueApplicationResponseDto>builder()
				                         .success(true)
				                         .message("basvuru onaylandi, venue olusturuldu.")
				                         .code(200)
				                         .data(response)
				                         .build());
	}
	
	@PostMapping(REJECT)
	public ResponseEntity<BaseResponse<VenueApplicationResponseDto>> rejectVenueApplication(@PathVariable UUID applicationId, @RequestParam String reason, @AuthenticationPrincipal UserDetailsImpl adminDetails) {
		log.info("Admin {} rejects venue application {} (reason: {})", adminDetails.getUsername(), applicationId, reason);
		var response = venueApplicationService.rejectApplication(applicationId, adminDetails.getUser().getId(), reason);
		return ResponseEntity.ok(BaseResponse.<VenueApplicationResponseDto>builder()
				                         .success(true)
				                         .message("Basvuru reddedildi")
				                         .code(200)
				                         .code(200)
				                         .data(response)
				                         .build());
	}
	
	// tum basvurulari getir
	@GetMapping(BY_STATUS)
	public ResponseEntity<BaseResponse<List<VenueApplicationResponseDto>>> getApplicationByStatus(@RequestParam ApplicationStatus status) {
		log.info("Admin requests venue applications by status: {}", status);
		var list = venueApplicationService.getApplicationsByStatus(status);
		return ResponseEntity.ok(BaseResponse.<List<VenueApplicationResponseDto>>builder()
				                         .success(true)
				                         .message("basvurular listelendi")
				                         .code(200)
				                         .data(list)
				                         .build());
	}
	
	// id ye gore basvuru getir
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
}