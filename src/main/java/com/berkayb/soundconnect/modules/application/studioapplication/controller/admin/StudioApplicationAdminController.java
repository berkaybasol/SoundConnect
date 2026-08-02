package com.berkayb.soundconnect.modules.application.studioapplication.controller.admin;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.application.studioapplication.dto.response.StudioApplicationResponseDto;
import com.berkayb.soundconnect.modules.application.studioapplication.service.StudioApplicationService;
import com.berkayb.soundconnect.modules.application.venueapplication.enums.ApplicationStatus;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

import static com.berkayb.soundconnect.shared.constant.EndPoints.StudioApplication.*;

@RestController
@RequestMapping(ADMIN_BASE)
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('MANAGE_STUDIO_APPLICATIONS')")
@Tag(name = "Admin / Studio Application", description = "Studio membership application review")
public class StudioApplicationAdminController {
	private final StudioApplicationService applicationService;

	@GetMapping(BY_STATUS)
	public ResponseEntity<BaseResponse<List<StudioApplicationResponseDto>>> byStatus(
			@RequestParam ApplicationStatus status
	) {
		return ResponseEntity.ok(BaseResponse.<List<StudioApplicationResponseDto>>builder()
				.success(true).code(200).message("Studyo basvurulari listelendi")
				.data(applicationService.getApplicationsByStatus(status)).build());
	}

	@GetMapping(GET_BY_ID)
	public ResponseEntity<BaseResponse<StudioApplicationResponseDto>> byId(@PathVariable UUID id) {
		return ResponseEntity.ok(BaseResponse.<StudioApplicationResponseDto>builder()
				.success(true).code(200).message("Studyo basvurusu getirildi")
				.data(applicationService.getById(id)).build());
	}

	@PostMapping(APPROVE)
	public ResponseEntity<BaseResponse<StudioApplicationResponseDto>> approve(
			@PathVariable UUID applicationId,
			@AuthenticationPrincipal UserDetailsImpl admin
	) {
		return ResponseEntity.ok(BaseResponse.<StudioApplicationResponseDto>builder()
				.success(true).code(200).message("Studyo basvurusu onaylandi")
				.data(applicationService.approveApplication(applicationId, admin.getId())).build());
	}

	@PostMapping(REJECT)
	public ResponseEntity<BaseResponse<StudioApplicationResponseDto>> reject(
			@PathVariable UUID applicationId,
			@RequestParam String reason,
			@AuthenticationPrincipal UserDetailsImpl admin
	) {
		return ResponseEntity.ok(BaseResponse.<StudioApplicationResponseDto>builder()
				.success(true).code(200).message("Studyo basvurusu reddedildi")
				.data(applicationService.rejectApplication(applicationId, admin.getId(), reason)).build());
	}
}
