package com.berkayb.soundconnect.modules.application.venueapplication.controller.admin;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.application.admin.PendingApplicationQuery;
import static com.berkayb.soundconnect.modules.application.admin.PendingApplicationQuery.Kind.VENUE;
import org.springframework.transaction.annotation.Transactional;
import com.berkayb.soundconnect.modules.application.venueapplication.dto.response.VenueApplicationResponseDto;
import com.berkayb.soundconnect.modules.application.venueapplication.enums.ApplicationStatus;
import com.berkayb.soundconnect.modules.application.venueapplication.service.VenueApplicationService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.CacheControl;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import com.berkayb.soundconnect.modules.application.admin.ApplicationRejectionRequest;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

import static com.berkayb.soundconnect.shared.constant.EndPoints.VenueApplication.*;

@RestController
@RequestMapping(ADMIN_BASE)
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasAuthority('ADMIN_PANEL_ACCESS') and hasAuthority('MANAGE_VENUE_APPLICATIONS') and !hasRole('LISTENER')")
@Tag(name = "FOR ADMIN / Venue Application", description = "Venue applications (admin operations)")
public class VenueApplicationAdminController {
	
	private final PendingApplicationQuery access;
	private final VenueApplicationService venueApplicationService;
	
	@Transactional
	@PostMapping(APPROVE)
	public ResponseEntity<BaseResponse<VenueApplicationResponseDto>> approveVenueApplication(
			@PathVariable UUID applicationId,
			@AuthenticationPrincipal UserDetailsImpl admin) {
		
		access.requireDecisionAccess(admin, VENUE);
		var response = venueApplicationService.approveApplication(applicationId, admin.getId());
		
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Decision-Id", response.decisionId().toString()).body(BaseResponse.<VenueApplicationResponseDto>builder()
		                                     .success(true)
		                                     .message("Basvuru onaylandı")
		                                     .code(200)
		                                     .data(response)
		                                     .build());
	}
	
	@Transactional
	@PostMapping(REJECT)
	public ResponseEntity<BaseResponse<VenueApplicationResponseDto>> rejectVenueApplication(
			@PathVariable UUID applicationId,
			@Valid @RequestBody(required = false) ApplicationRejectionRequest body,
			@RequestParam(required = false) String reason,
			@AuthenticationPrincipal UserDetailsImpl admin) {
		
		access.requireDecisionAccess(admin, VENUE);
		if (body != null && reason != null) throw new SoundConnectException(ErrorType.VALIDATION_ERROR);
		var response = venueApplicationService.rejectApplication(applicationId, admin.getId(), body == null ? reason : body.reason());
		
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Decision-Id", response.decisionId().toString()).body(BaseResponse.<VenueApplicationResponseDto>builder()
		                                     .success(true)
		                                     .message("Basvuru reddedildi")
		                                     .code(200)
		                                     .data(response)
		                                     .build());
	}
	
	@Transactional(readOnly = true)
	@GetMapping(BY_STATUS)
	public ResponseEntity<BaseResponse<List<VenueApplicationResponseDto>>> getApplicationByStatus(
			@RequestParam ApplicationStatus status, @AuthenticationPrincipal UserDetailsImpl admin) {
        access.requireAccess(admin, VENUE);
		
		log.info("Admin requests venue applications by status: {}", status);
		var list = venueApplicationService.getApplicationsByStatus(status);
		
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(BaseResponse.<List<VenueApplicationResponseDto>>builder()
		                                     .success(true)
		                                     .message("basvurular listelendi")
		                                     .code(200)
		                                     .data(list)
		                                     .build());
	}
	
	@Transactional(readOnly = true)
	@GetMapping(GET_BY_ID)
	public ResponseEntity<BaseResponse<VenueApplicationResponseDto>> getVenueApplicationById(
            @PathVariable UUID id, @AuthenticationPrincipal UserDetailsImpl admin) {
        access.requireAccess(admin, VENUE);
		log.info("Admin requests venue application by id: {}", id);
		var response = venueApplicationService.getById(id);
		
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(BaseResponse.<VenueApplicationResponseDto>builder()
		                                     .success(true)
		                                     .message("Basvuru getirildi")
		                                     .code(200)
		                                     .data(response)
		                                     .build());
	}
	
}
