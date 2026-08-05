package com.berkayb.soundconnect.modules.studio.room.controller;

import com.berkayb.soundconnect.modules.studio.reservation.dto.response.StudioRoomAvailabilityResponse;
import com.berkayb.soundconnect.modules.studio.reservation.service.StudioReservationService;
import com.berkayb.soundconnect.modules.studio.room.dto.response.StudioPageResponse;
import com.berkayb.soundconnect.modules.studio.room.dto.response.StudioRoomPublicResponse;
import com.berkayb.soundconnect.modules.studio.room.service.StudioRoomService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/public/studio-profiles/{profileId}/rooms")
@RequiredArgsConstructor
@Validated
@Tag(name = "PUBLIC / Studio Rooms", description = "Public studio rooms and availability")
public class StudioRoomPublicController {
    private final StudioRoomService roomService;
    private final StudioReservationService reservationService;

    @GetMapping
    public ResponseEntity<BaseResponse<StudioPageResponse<StudioRoomPublicResponse>>> list(
            @PathVariable UUID profileId,
            @RequestParam(defaultValue = "0") @Min(0) @Max(1000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size
    ) {
        return ResponseEntity.ok(success(
                "Odalar getirildi",
                roomService.listPublic(profileId, page, size)
        ));
    }

    @GetMapping("/{roomId}")
    public ResponseEntity<BaseResponse<StudioRoomPublicResponse>> get(
            @PathVariable UUID profileId,
            @PathVariable UUID roomId
    ) {
        return ResponseEntity.ok(success(
                "Oda getirildi",
                roomService.getPublic(profileId, roomId)
        ));
    }

    @GetMapping("/{roomId}/availability")
    public ResponseEntity<BaseResponse<StudioRoomAvailabilityResponse>> availability(
            @PathVariable UUID profileId,
            @PathVariable UUID roomId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        return ResponseEntity.ok(success(
                "Musaitlik takvimi getirildi",
                reservationService.publicAvailability(profileId, roomId, from, to)
        ));
    }

    private static <T> BaseResponse<T> success(String message, T data) {
        return BaseResponse.<T>builder()
                .success(true)
                .code(HttpStatus.OK.value())
                .message(message)
                .data(data)
                .build();
    }
}
