package com.berkayb.soundconnect.modules.studio.reservation.controller;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.studio.reservation.dto.request.StudioReservationCreateRequest;
import com.berkayb.soundconnect.modules.studio.reservation.dto.request.StudioVersionRequest;
import com.berkayb.soundconnect.modules.studio.reservation.dto.response.StudioReservationResponse;
import com.berkayb.soundconnect.modules.studio.reservation.service.StudioReservationService;
import com.berkayb.soundconnect.modules.studio.room.dto.response.StudioPageResponse;
import com.berkayb.soundconnect.shared.response.BaseResponse;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/user/studio-reservations")
@RequiredArgsConstructor
@Validated
@PreAuthorize("isAuthenticated()")
@Tag(name = "FOR USERS / Studio Reservations", description = "Customer studio reservations")
public class StudioReservationUserController {
    private final StudioReservationService reservationService;

    @PostMapping
    public ResponseEntity<BaseResponse<StudioReservationResponse>> create(
            @AuthenticationPrincipal UserDetailsImpl principal,
            @Valid @RequestBody StudioReservationCreateRequest request
    ) {
        StudioReservationResponse data = reservationService.create(principal.getId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(success(
                HttpStatus.CREATED,
                "Rezervasyon kaydedildi",
                data
        ));
    }

    @GetMapping
    public ResponseEntity<BaseResponse<StudioPageResponse<StudioReservationResponse>>> list(
            @AuthenticationPrincipal UserDetailsImpl principal,
            @RequestParam(defaultValue = "0") @Min(0) @Max(1000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ResponseEntity.ok(success(
                HttpStatus.OK,
                "Rezervasyonlar getirildi",
                reservationService.listCustomer(principal.getId(), page, size)
        ));
    }

    @GetMapping("/rooms/{roomId}")
    public ResponseEntity<BaseResponse<StudioPageResponse<StudioReservationResponse>>> listRoomDate(
            @AuthenticationPrincipal UserDetailsImpl principal,
            @PathVariable UUID roomId,
            @RequestParam LocalDate date,
            @RequestParam(defaultValue = "0") @Min(0) @Max(1000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ResponseEntity.ok(success(
                HttpStatus.OK,
                "Odaya ait rezervasyonlar getirildi",
                reservationService.listCustomerRoomDate(
                        principal.getId(),
                        roomId,
                        date,
                        page,
                        size
                )
        ));
    }

    @PostMapping("/{reservationId}/cancel")
    public ResponseEntity<BaseResponse<StudioReservationResponse>> cancel(
            @AuthenticationPrincipal UserDetailsImpl principal,
            @PathVariable UUID reservationId,
            @Valid @RequestBody StudioVersionRequest request
    ) {
        return ResponseEntity.ok(success(
                HttpStatus.OK,
                "Rezervasyon iptal edildi",
                reservationService.cancelCustomer(principal.getId(), reservationId, request)
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
