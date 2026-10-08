package com.berkayb.soundconnect.modules.marketplace.controller;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.marketplace.MarketplaceTypes.ReportStatus;
import com.berkayb.soundconnect.modules.marketplace.dto.MarketplaceRequests.Review;
import com.berkayb.soundconnect.modules.marketplace.dto.MarketplaceResponses.AdminReport;
import com.berkayb.soundconnect.modules.marketplace.service.MarketplaceService;
import com.berkayb.soundconnect.shared.response.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/marketplace/reports")
@PreAuthorize("!hasRole('LISTENER') and hasAuthority('MANAGE_MARKETPLACE_REPORTS')")
public class MarketplaceAdminController {
    private final MarketplaceService service;
    @GetMapping
    public ResponseEntity<BaseResponse<PageResponse<AdminReport>>> reports(@AuthenticationPrincipal UserDetailsImpl principal,
            @RequestParam(required=false) ReportStatus status,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return MarketplaceController.ok(service.reports(MarketplaceController.user(principal),status,page,size));
    }
    @PostMapping("/{reportId}/review")
    public ResponseEntity<BaseResponse<AdminReport>> review(@AuthenticationPrincipal UserDetailsImpl principal,
            @PathVariable UUID reportId,@Valid @RequestBody Review body) {
        return MarketplaceController.ok(service.review(MarketplaceController.user(principal),reportId,body));
    }
}
