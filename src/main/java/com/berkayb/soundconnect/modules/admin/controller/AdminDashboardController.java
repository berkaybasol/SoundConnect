package com.berkayb.soundconnect.modules.admin.controller;

import com.berkayb.soundconnect.modules.admin.dto.AdminDashboardSummaryDto;
import com.berkayb.soundconnect.modules.admin.service.AdminDashboardService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.berkayb.soundconnect.shared.constant.EndPoints.AdminDashboard.BASE;
import static com.berkayb.soundconnect.shared.constant.EndPoints.AdminDashboard.SUMMARY;

@RestController
@RequestMapping(BASE)
@RequiredArgsConstructor
@Tag(name = "FOR ADMIN / Dashboard", description = "Admin panel summary endpoints")
public class AdminDashboardController {
	private final AdminDashboardService adminDashboardService;

	@PreAuthorize("hasAuthority('ADMIN_PANEL_ACCESS')")
	@GetMapping(SUMMARY)
	public ResponseEntity<BaseResponse<AdminDashboardSummaryDto>> getSummary() {
		return ResponseEntity.ok(BaseResponse.<AdminDashboardSummaryDto>builder()
				.success(true)
				.message("Admin dashboard summary fetched")
				.code(200)
				.data(adminDashboardService.getSummary())
				.build());
	}
}
