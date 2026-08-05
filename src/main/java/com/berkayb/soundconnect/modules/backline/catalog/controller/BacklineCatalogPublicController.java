package com.berkayb.soundconnect.modules.backline.catalog.controller;

import com.berkayb.soundconnect.modules.backline.catalog.dto.BacklineCategoryTreeResponse;
import com.berkayb.soundconnect.modules.backline.catalog.service.BacklineCatalogService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import com.berkayb.soundconnect.shared.response.PageResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/public/backline/categories")
@Tag(name = "Backline Catalog - Public", description = "Global active two-level backline catalog")
public class BacklineCatalogPublicController {
    private final BacklineCatalogService catalogService;

    @GetMapping
    public BaseResponse<PageResponse<BacklineCategoryTreeResponse>> list(
            @RequestParam(defaultValue = "0") @Min(0) @Max(1000) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size
    ) {
        return BaseResponse.<PageResponse<BacklineCategoryTreeResponse>>builder()
                .success(true)
                .code(200)
                .message("Backline catalog fetched")
                .data(PageResponse.from(catalogService.listPublicCategories(page, size)))
                .build();
    }
}
