package com.berkayb.soundconnect.modules.backline.catalog.controller;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.backline.catalog.dto.BacklineCategoryRequestCreateRequest;
import com.berkayb.soundconnect.modules.backline.catalog.dto.BacklineCategoryRequestResponse;
import com.berkayb.soundconnect.modules.backline.catalog.service.BacklineCatalogService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
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
@PreAuthorize("hasRole('STUDIO')")
@RequestMapping("/api/v1/user/studio-profiles/me/category-requests")
@Tag(name = "Backline Category Requests - Owner", description = "Studio-owned backline category requests")
public class BacklineCategoryRequestOwnerController {
    private final BacklineCatalogService catalogService;

    @PostMapping
    public ResponseEntity<BaseResponse<BacklineCategoryRequestResponse>> submit(
            @AuthenticationPrincipal UserDetailsImpl principal,
            @Valid @RequestBody BacklineCategoryRequestCreateRequest request
    ) {
        BacklineCategoryRequestResponse data = catalogService.submitRequest(principal.getId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response(201, "Category request submitted", data));
    }

    @GetMapping
    public BaseResponse<Page<BacklineCategoryRequestResponse>> list(
            @AuthenticationPrincipal UserDetailsImpl principal,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size
    ) {
        return response(200, "Category requests listed", catalogService.listOwnerRequests(principal.getId(), page, size));
    }

    @DeleteMapping("/{requestId}")
    public BaseResponse<BacklineCategoryRequestResponse> withdraw(
            @AuthenticationPrincipal UserDetailsImpl principal,
            @PathVariable UUID requestId
    ) {
        return response(
                200,
                "Category request withdrawn",
                catalogService.withdrawRequest(principal.getId(), requestId)
        );
    }

    private static <T> BaseResponse<T> response(int code, String message, T data) {
        return BaseResponse.<T>builder()
                .success(true)
                .code(code)
                .message(message)
                .data(data)
                .build();
    }
}
