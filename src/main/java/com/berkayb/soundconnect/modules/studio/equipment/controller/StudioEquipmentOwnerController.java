package com.berkayb.soundconnect.modules.studio.equipment.controller;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.studio.equipment.dto.EquipmentAvailabilityCommandResponse;
import com.berkayb.soundconnect.modules.studio.equipment.dto.EquipmentAvailabilityMoveRequest;
import com.berkayb.soundconnect.modules.studio.equipment.dto.EquipmentAvailabilityRangeResponse;
import com.berkayb.soundconnect.modules.studio.equipment.dto.EquipmentCreateRequest;
import com.berkayb.soundconnect.modules.studio.equipment.dto.EquipmentOwnerResponse;
import com.berkayb.soundconnect.modules.studio.equipment.dto.EquipmentUpdateRequest;
import com.berkayb.soundconnect.modules.studio.equipment.model.EquipmentAvailabilityBucket;
import com.berkayb.soundconnect.modules.studio.equipment.service.StudioEquipmentService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

@Validated
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasRole('STUDIO')")
@RequestMapping("/api/v1/user/studio-profiles/me/equipment")
@Tag(name = "Studio Equipment - Owner", description = "Studio-owned equipment inventory and daily availability")
public class StudioEquipmentOwnerController {
    private final StudioEquipmentService equipmentService;

    @PostMapping
    public ResponseEntity<BaseResponse<EquipmentOwnerResponse>> create(
            @AuthenticationPrincipal UserDetailsImpl principal,
            @Valid @RequestBody EquipmentCreateRequest request
    ) {
        EquipmentOwnerResponse data = equipmentService.create(principal.getId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response(201, "Equipment created", data));
    }

    @GetMapping
    public BaseResponse<Page<EquipmentOwnerResponse>> list(
            @AuthenticationPrincipal UserDetailsImpl principal,
            @RequestParam(required = false) @Size(max = 100) String query,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) EquipmentAvailabilityBucket availabilityBucket,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size
    ) {
        return response(
                200,
                "Equipment listed",
                equipmentService.listOwner(
                        principal.getId(), query, categoryId, availabilityBucket, page, size
                )
        );
    }

    @GetMapping("/{equipmentId}")
    public BaseResponse<EquipmentOwnerResponse> get(
            @AuthenticationPrincipal UserDetailsImpl principal,
            @PathVariable UUID equipmentId
    ) {
        return response(200, "Equipment fetched", equipmentService.getOwner(principal.getId(), equipmentId));
    }

    @PutMapping("/{equipmentId}")
    public BaseResponse<EquipmentOwnerResponse> update(
            @AuthenticationPrincipal UserDetailsImpl principal,
            @PathVariable UUID equipmentId,
            @Valid @RequestBody EquipmentUpdateRequest request
    ) {
        return response(200, "Equipment updated", equipmentService.update(principal.getId(), equipmentId, request));
    }

    @DeleteMapping("/{equipmentId}")
    public BaseResponse<Void> archive(
            @AuthenticationPrincipal UserDetailsImpl principal,
            @PathVariable UUID equipmentId,
            @RequestParam @PositiveOrZero long expectedVersion
    ) {
        equipmentService.archive(principal.getId(), equipmentId, expectedVersion);
        return response(200, "Equipment archived", null);
    }

    @GetMapping("/{equipmentId}/availability")
    public BaseResponse<EquipmentAvailabilityRangeResponse> availability(
            @AuthenticationPrincipal UserDetailsImpl principal,
            @PathVariable UUID equipmentId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate
    ) {
        return response(
                200,
                "Equipment availability fetched",
                equipmentService.getOwnerAvailability(principal.getId(), equipmentId, startDate, endDate)
        );
    }

    @PostMapping("/{equipmentId}/availability/commands")
    public BaseResponse<EquipmentAvailabilityCommandResponse> moveAvailability(
            @AuthenticationPrincipal UserDetailsImpl principal,
            @PathVariable UUID equipmentId,
            @Valid @RequestBody EquipmentAvailabilityMoveRequest request
    ) {
        return response(
                200,
                "Equipment availability updated",
                equipmentService.moveAvailability(principal.getId(), equipmentId, request)
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
