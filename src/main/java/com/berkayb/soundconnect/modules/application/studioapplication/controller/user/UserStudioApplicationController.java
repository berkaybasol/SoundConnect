package com.berkayb.soundconnect.modules.application.studioapplication.controller.user;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.application.studioapplication.dto.request.StudioApplicationCreateRequestDto;
import com.berkayb.soundconnect.modules.application.studioapplication.dto.response.StudioApplicationResponseDto;
import com.berkayb.soundconnect.modules.application.studioapplication.service.StudioApplicationService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import com.berkayb.soundconnect.shared.response.PageResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static com.berkayb.soundconnect.shared.constant.EndPoints.StudioApplication.*;

@RestController
@RequestMapping(USER_BASE)
@RequiredArgsConstructor
@Validated
@PreAuthorize("isAuthenticated()")
@Tag(name = "User / Studio Application", description = "Studio membership applications")
public class UserStudioApplicationController {
	private final StudioApplicationService applicationService;

	@PostMapping(CREATE)
	public ResponseEntity<BaseResponse<StudioApplicationResponseDto>> create(
			@AuthenticationPrincipal UserDetailsImpl userDetails,
			@Valid @RequestBody StudioApplicationCreateRequestDto request
	) {
		StudioApplicationResponseDto response = applicationService.createApplication(userDetails.getId(), request);
		return ResponseEntity.status(HttpStatus.CREATED).body(BaseResponse.<StudioApplicationResponseDto>builder()
				.success(true).code(HttpStatus.CREATED.value()).message("Studyo basvurusu olusturuldu")
				.data(response).build());
	}

	@GetMapping(MY_APPLICATIONS)
	public ResponseEntity<BaseResponse<PageResponse<StudioApplicationResponseDto>>> mine(
			@AuthenticationPrincipal UserDetailsImpl userDetails,
			@RequestParam(defaultValue = "0") @Min(0) @Max(1000) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
	) {
		return ResponseEntity.ok(BaseResponse.<PageResponse<StudioApplicationResponseDto>>builder()
				.success(true).code(200).message("Studyo basvurulari listelendi")
				.data(applicationService.getApplicationsByUser(userDetails.getId(), page, size)).build());
	}

	@GetMapping(MY_PENDING)
	public ResponseEntity<BaseResponse<StudioApplicationResponseDto>> pending(
			@AuthenticationPrincipal UserDetailsImpl userDetails
	) {
		return ResponseEntity.ok(BaseResponse.<StudioApplicationResponseDto>builder()
				.success(true).code(200).message("Bekleyen studyo basvurusu getirildi")
				.data(applicationService.getPendingApplicationByUser(userDetails.getId())).build());
	}
}
