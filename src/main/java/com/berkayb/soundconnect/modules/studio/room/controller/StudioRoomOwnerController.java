package com.berkayb.soundconnect.modules.studio.room.controller;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.studio.reservation.dto.request.StudioManualBlockCreateRequest;
import com.berkayb.soundconnect.modules.studio.reservation.dto.request.StudioManualBlockReleaseRequest;
import com.berkayb.soundconnect.modules.studio.reservation.dto.request.StudioVersionRequest;
import com.berkayb.soundconnect.modules.studio.reservation.dto.response.StudioOccupancyOwnerResponse;
import com.berkayb.soundconnect.modules.studio.reservation.dto.response.StudioReservationOwnerResponse;
import com.berkayb.soundconnect.modules.studio.reservation.dto.response.StudioRoomScheduleResponse;
import com.berkayb.soundconnect.modules.studio.reservation.service.StudioReservationService;
import com.berkayb.soundconnect.modules.studio.room.dto.request.StudioRoomArchiveRequest;
import com.berkayb.soundconnect.modules.studio.room.dto.request.StudioRoomCreateRequest;
import com.berkayb.soundconnect.modules.studio.room.dto.request.StudioRoomUpdateRequest;
import com.berkayb.soundconnect.modules.studio.room.dto.response.StudioPageResponse;
import com.berkayb.soundconnect.modules.studio.room.dto.response.StudioRoomOwnerResponse;
import com.berkayb.soundconnect.modules.studio.room.service.StudioRoomService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
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

@RestController
@RequestMapping("/api/v1/user/studio-profiles/me/rooms")
@RequiredArgsConstructor
@Validated
@PreAuthorize("hasRole('STUDIO')")
@Tag(name = "FOR USERS / Studio Rooms", description = "Studio owner room and schedule management")
public class StudioRoomOwnerController {
    private final StudioRoomService roomService;
    private final StudioReservationService reservationService;

    @PostMapping
    public ResponseEntity<BaseResponse<StudioRoomOwnerResponse>> create(
            @AuthenticationPrincipal UserDetailsImpl principal,
            @Valid @RequestBody StudioRoomCreateRequest request
    ) {
        StudioRoomOwnerResponse data = roomService.create(principal.getId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(success(HttpStatus.CREATED, "Oda olusturuldu", data));
    }

    @GetMapping
    public ResponseEntity<BaseResponse<StudioPageResponse<StudioRoomOwnerResponse>>> list(
            @AuthenticationPrincipal UserDetailsImpl principal,
            @RequestParam(defaultValue = "0") @Min(0) @Max(1000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size
    ) {
        return ResponseEntity.ok(success(
                HttpStatus.OK,
                "Odalar getirildi",
                roomService.listOwner(principal.getId(), page, size)
        ));
    }

    @GetMapping("/{roomId}")
    public ResponseEntity<BaseResponse<StudioRoomOwnerResponse>> get(
            @AuthenticationPrincipal UserDetailsImpl principal,
            @PathVariable UUID roomId
    ) {
        return ResponseEntity.ok(success(
                HttpStatus.OK,
                "Oda getirildi",
                roomService.getOwner(principal.getId(), roomId)
        ));
    }

    @PutMapping("/{roomId}")
    public ResponseEntity<BaseResponse<StudioRoomOwnerResponse>> update(
            @AuthenticationPrincipal UserDetailsImpl principal,
            @PathVariable UUID roomId,
            @Valid @RequestBody StudioRoomUpdateRequest request
    ) {
        return ResponseEntity.ok(success(
                HttpStatus.OK,
                "Oda guncellendi",
                roomService.update(principal.getId(), roomId, request)
        ));
    }

    @DeleteMapping("/{roomId}")
    public ResponseEntity<BaseResponse<Void>> archive(
            @AuthenticationPrincipal UserDetailsImpl principal,
            @PathVariable UUID roomId,
            @Valid @RequestBody StudioRoomArchiveRequest request
    ) {
        roomService.archive(principal.getId(), roomId, request);
        return ResponseEntity.ok(success(HttpStatus.OK, "Oda arsivlendi", null));
    }

    @GetMapping("/{roomId}/schedule")
    public ResponseEntity<BaseResponse<StudioRoomScheduleResponse>> schedule(
            @AuthenticationPrincipal UserDetailsImpl principal,
            @PathVariable UUID roomId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") @Min(0) @Max(1000) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size
    ) {
        return ResponseEntity.ok(success(
                HttpStatus.OK,
                "Oda takvimi getirildi",
                reservationService.ownerSchedule(principal.getId(), roomId, from, to, page, size)
        ));
    }

    @PostMapping("/{roomId}/reservations/{reservationId}/approve")
    public ResponseEntity<BaseResponse<StudioReservationOwnerResponse>> approve(
            @AuthenticationPrincipal UserDetailsImpl principal,
            @PathVariable UUID roomId,
            @PathVariable UUID reservationId,
            @Valid @RequestBody StudioVersionRequest request
    ) {
        return ResponseEntity.ok(success(
                HttpStatus.OK,
                "Rezervasyon onaylandi",
                reservationService.approve(principal.getId(), roomId, reservationId, request)
        ));
    }

    @PostMapping("/{roomId}/reservations/{reservationId}/reject")
    public ResponseEntity<BaseResponse<StudioReservationOwnerResponse>> reject(
            @AuthenticationPrincipal UserDetailsImpl principal,
            @PathVariable UUID roomId,
            @PathVariable UUID reservationId,
            @Valid @RequestBody StudioVersionRequest request
    ) {
        return ResponseEntity.ok(success(
                HttpStatus.OK,
                "Rezervasyon reddedildi",
                reservationService.reject(principal.getId(), roomId, reservationId, request)
        ));
    }

    @PostMapping("/{roomId}/reservations/{reservationId}/cancel")
    public ResponseEntity<BaseResponse<StudioReservationOwnerResponse>> cancel(
            @AuthenticationPrincipal UserDetailsImpl principal,
            @PathVariable UUID roomId,
            @PathVariable UUID reservationId,
            @Valid @RequestBody StudioVersionRequest request
    ) {
        return ResponseEntity.ok(success(
                HttpStatus.OK,
                "Rezervasyon iptal edildi",
                reservationService.cancelOwner(principal.getId(), roomId, reservationId, request)
        ));
    }

    @PostMapping("/{roomId}/blocks")
    public ResponseEntity<BaseResponse<StudioOccupancyOwnerResponse>> createBlock(
            @AuthenticationPrincipal UserDetailsImpl principal,
            @PathVariable UUID roomId,
            @Valid @RequestBody StudioManualBlockCreateRequest request
    ) {
        StudioOccupancyOwnerResponse data = reservationService.createManualBlock(
                principal.getId(), roomId, request
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(success(
                HttpStatus.CREATED,
                "Saat araligi dolu olarak isaretlendi",
                data
        ));
    }

    @DeleteMapping("/{roomId}/blocks/{blockId}")
    public ResponseEntity<BaseResponse<StudioOccupancyOwnerResponse>> releaseBlock(
            @AuthenticationPrincipal UserDetailsImpl principal,
            @PathVariable UUID roomId,
            @PathVariable UUID blockId,
            @Valid @RequestBody StudioManualBlockReleaseRequest request
    ) {
        return ResponseEntity.ok(success(
                HttpStatus.OK,
                "Manuel doluluk kaldirildi",
                reservationService.releaseManualBlock(principal.getId(), roomId, blockId, request)
        ));
    }

    private static <T> BaseResponse<T> success(HttpStatus status, String message, T data) {
        return BaseResponse.<T>builder()
                .success(true)
                .code(status.value())
                .message(message)
                .data(data)
                .build();
    }
}
