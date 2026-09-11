package com.berkayb.soundconnect.modules.feed.musician.delivery;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.feed.musician.api.*;
import com.berkayb.soundconnect.modules.feed.musician.abuse.MusicianFeedRateLimitGuard;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.feed.musician", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequestMapping("/api/v1/feed/musician")
@PreAuthorize("hasRole('MUSICIAN')")
public class MusicianFeedTelemetryController {
    private final MusicianFeedTelemetryService service;
    private final MusicianFeedRateLimitGuard rateLimitGuard;

    @PostMapping("/events")
    public ResponseEntity<BaseResponse<MusicianFeedTelemetryResponse>> record(
            @AuthenticationPrincipal UserDetailsImpl principal,
            @Valid @RequestBody MusicianFeedTelemetryRequest request
    ) {
        if (principal == null) throw new SoundConnectException(ErrorType.UNAUTHORIZED);
        rateLimitGuard.checkTelemetry(principal.getId());
        return ResponseEntity.ok(BaseResponse.<MusicianFeedTelemetryResponse>builder()
                .success(true).message("Akış olayı kaydedildi.").code(200)
                .data(service.record(principal.getId(), request)).build());
    }
}
