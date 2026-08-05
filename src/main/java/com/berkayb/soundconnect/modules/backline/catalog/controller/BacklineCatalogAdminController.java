package com.berkayb.soundconnect.modules.backline.catalog.controller;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.backline.catalog.dto.BacklineCategoryRequestResponse;
import com.berkayb.soundconnect.modules.backline.catalog.dto.BacklineCategoryReviewRequest;
import com.berkayb.soundconnect.modules.backline.catalog.model.BacklineCategoryRequestStatus;
import com.berkayb.soundconnect.modules.backline.catalog.service.BacklineCatalogService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import com.berkayb.soundconnect.shared.response.PageResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
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

@Validated
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('MANAGE_BACKLINE_CATALOG')")
@RequestMapping("/api/v1/admin/backline/category-requests")
@Tag(name = "Backline Catalog - Admin", description = "Backline category request review")
public class BacklineCatalogAdminController {
    private final BacklineCatalogService catalogService;

    @GetMapping
    public BaseResponse<PageResponse<BacklineCategoryRequestResponse>> list(
            @RequestParam(required = false) BacklineCategoryRequestStatus status,
            @RequestParam(defaultValue = "0") @Min(0) @Max(1000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size
    ) {
        return response(
                "Category requests listed",
                PageResponse.from(catalogService.listAdminRequests(status, page, size))
        );
    }

    @PostMapping("/{requestId}/review")
    public BaseResponse<BacklineCategoryRequestResponse> review(
            @AuthenticationPrincipal UserDetailsImpl principal,
            @PathVariable UUID requestId,
            @Valid @RequestBody BacklineCategoryReviewRequest request
    ) {
        return response(
                "Category request reviewed",
                catalogService.reviewRequest(principal.getId(), requestId, request)
        );
    }

    private static <T> BaseResponse<T> response(String message, T data) {
        return BaseResponse.<T>builder()
                .success(true)
                .code(200)
                .message(message)
                .data(data)
                .build();
    }
}
