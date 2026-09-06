package com.berkayb.soundconnect.modules.event.publication;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.event.enums.PerformerType;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.calendar.abuse.MusicianCalendarRateLimitGuard;
import com.berkayb.soundconnect.shared.response.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/v1/user/event-profile-publications")
@PreAuthorize("hasRole('MUSICIAN')")
public class EventProfilePublicationController {
    private final EventProfilePublicationService service;
    private final MusicianCalendarRateLimitGuard guard;

    @GetMapping
    public ResponseEntity<BaseResponse<PageResponse<EventProfilePublicationDto>>> getMine(
            @AuthenticationPrincipal UserDetailsImpl principal, @RequestParam PerformerType targetType,
            @RequestParam UUID targetId, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "ALL") EventPublicationPeriod period) {
        return response(service.getMine(principal.getUser().getId(), targetType, targetId, page, size, period));
    }

    @PutMapping("/{eventId}")
    public ResponseEntity<BaseResponse<EventProfilePublicationDto>> update(
            @AuthenticationPrincipal UserDetailsImpl principal, @PathVariable UUID eventId,
            @Valid @RequestBody EventProfilePublicationUpdate update) {
        UUID userId = principal.getUser().getId();
        service.requireAuthority(userId, update.targetType(), update.targetId());
        if (update.targetType() == PerformerType.BAND) guard.checkBand(update.targetId());
        else guard.check(userId);
        return response(service.update(userId, eventId, update));
    }

    private static <T> ResponseEntity<BaseResponse<T>> response(T data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate())
                .body(BaseResponse.<T>builder().success(true).code(200).message("Etkinlik profil tercihi güncellendi.").data(data).build());
    }
}
