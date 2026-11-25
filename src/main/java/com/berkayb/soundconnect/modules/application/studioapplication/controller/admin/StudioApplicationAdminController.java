package com.berkayb.soundconnect.modules.application.studioapplication.controller.admin;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.application.admin.PendingApplicationQuery;
import static com.berkayb.soundconnect.modules.application.admin.PendingApplicationQuery.Kind.STUDIO;
import org.springframework.transaction.annotation.Transactional;
import com.berkayb.soundconnect.modules.application.admin.ApplicationRejectionRequest;
import com.berkayb.soundconnect.modules.application.studioapplication.dto.response.StudioApplicationResponseDto;
import com.berkayb.soundconnect.modules.application.studioapplication.service.StudioApplicationService;
import com.berkayb.soundconnect.modules.application.venueapplication.enums.ApplicationStatus;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import com.berkayb.soundconnect.shared.response.PageResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.http.CacheControl;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static com.berkayb.soundconnect.shared.constant.EndPoints.StudioApplication.*;

@RestController
@RequestMapping(ADMIN_BASE)
@RequiredArgsConstructor
@Validated
@PreAuthorize("hasAuthority('ADMIN_PANEL_ACCESS') and hasAuthority('MANAGE_STUDIO_APPLICATIONS') and !hasRole('LISTENER')")
@Tag(name = "Admin / Studio Application", description = "Studio membership application review")
public class StudioApplicationAdminController {
	private final PendingApplicationQuery access;
	private final StudioApplicationService applicationService;

	@Transactional(readOnly = true)
	@GetMapping(BY_STATUS)
	public ResponseEntity<BaseResponse<PageResponse<StudioApplicationResponseDto>>> byStatus(
			@RequestParam ApplicationStatus status,
			@RequestParam(defaultValue = "0") @Min(0) @Max(1000) int page,
			@RequestParam(defaultValue = "50") @Min(1) @Max(100) int size,
            @AuthenticationPrincipal UserDetailsImpl admin
	) {
        access.requireAccess(admin, STUDIO);
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(BaseResponse.<PageResponse<StudioApplicationResponseDto>>builder()
				.success(true).code(200).message("Studyo basvurulari listelendi")
				.data(applicationService.getApplicationsByStatus(status, page, size)).build());
	}

	@Transactional(readOnly = true)
	@GetMapping(GET_BY_ID)
	public ResponseEntity<BaseResponse<StudioApplicationResponseDto>> byId(
            @PathVariable UUID id, @AuthenticationPrincipal UserDetailsImpl admin) {
        access.requireAccess(admin, STUDIO);
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(BaseResponse.<StudioApplicationResponseDto>builder()
				.success(true).code(200).message("Studyo basvurusu getirildi")
				.data(applicationService.getById(id)).build());
	}

	@Transactional
	@PostMapping(APPROVE)
	public ResponseEntity<BaseResponse<StudioApplicationResponseDto>> approve(
			@PathVariable UUID applicationId,
			@AuthenticationPrincipal UserDetailsImpl admin
	) {
		access.requireDecisionAccess(admin, STUDIO);
        var response = applicationService.approveApplication(applicationId, admin.getId());
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Decision-Id", response.decisionId().toString()).body(BaseResponse.<StudioApplicationResponseDto>builder()
				.success(true).code(200).message("Studyo basvurusu onaylandi")
				.data(response).build());
	}

	@Transactional
	@PostMapping(REJECT)
	public ResponseEntity<BaseResponse<StudioApplicationResponseDto>> reject(
			@PathVariable UUID applicationId,
			@Valid @RequestBody ApplicationRejectionRequest request,
			@AuthenticationPrincipal UserDetailsImpl admin
	) {
		access.requireDecisionAccess(admin, STUDIO);
        var response = applicationService.rejectApplication(applicationId, admin.getId(), request.reason());
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Decision-Id", response.decisionId().toString()).body(BaseResponse.<StudioApplicationResponseDto>builder()
				.success(true).code(200).message("Studyo basvurusu reddedildi")
				.data(response).build());
	}
}
