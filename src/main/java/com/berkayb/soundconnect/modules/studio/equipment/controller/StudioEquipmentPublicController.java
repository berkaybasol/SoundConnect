package com.berkayb.soundconnect.modules.studio.equipment.controller;

import com.berkayb.soundconnect.modules.studio.equipment.dto.EquipmentAvailabilityRangeResponse;
import com.berkayb.soundconnect.modules.studio.equipment.dto.EquipmentPublicResponse;
import com.berkayb.soundconnect.modules.studio.equipment.model.EquipmentAvailabilityBucket;
import com.berkayb.soundconnect.modules.studio.equipment.service.StudioEquipmentService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/public/studio-profiles/{studioProfileId}/equipment")
@Tag(name = "Studio Equipment - Public", description = "Public equipment inventory and daily availability")
public class StudioEquipmentPublicController {
    private final StudioEquipmentService equipmentService;

    @GetMapping
    public BaseResponse<Page<EquipmentPublicResponse>> list(
            @PathVariable UUID studioProfileId,
            @RequestParam(required = false) @Size(max = 100) String query,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) EquipmentAvailabilityBucket availabilityBucket,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size
    ) {
        return response(
                "Equipment listed",
                equipmentService.listPublic(
                        studioProfileId, query, categoryId, availabilityBucket, page, size
                )
        );
    }

    @GetMapping("/{equipmentId}")
    public BaseResponse<EquipmentPublicResponse> get(
            @PathVariable UUID studioProfileId,
            @PathVariable UUID equipmentId
    ) {
        return response("Equipment fetched", equipmentService.getPublic(studioProfileId, equipmentId));
    }

    @GetMapping("/{equipmentId}/availability")
    public BaseResponse<EquipmentAvailabilityRangeResponse> availability(
            @PathVariable UUID studioProfileId,
            @PathVariable UUID equipmentId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate
    ) {
        return response(
                "Equipment availability fetched",
                equipmentService.getPublicAvailability(studioProfileId, equipmentId, startDate, endDate)
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
