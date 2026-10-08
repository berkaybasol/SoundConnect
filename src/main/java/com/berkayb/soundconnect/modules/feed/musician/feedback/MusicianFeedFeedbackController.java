package com.berkayb.soundconnect.modules.feed.musician.feedback;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.feed.musician.abuse.MusicianFeedRateLimitGuard;
import com.berkayb.soundconnect.modules.feed.musician.api.*;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.http.CacheControl;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.feed.musician", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequestMapping("/api/v1/feed/musician")
@PreAuthorize("hasRole('MUSICIAN')")
public class MusicianFeedFeedbackController {
    private final MusicianFeedFeedbackService service;
    private final MusicianFeedMutedAuthorsService mutedAuthors;
    private final MusicianFeedRateLimitGuard rateLimitGuard;

    @GetMapping("/muted-authors")
    public ResponseEntity<BaseResponse<MusicianFeedMutedAuthorsResponse>> mutedAuthors(
            @AuthenticationPrincipal UserDetailsImpl principal,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor
    ) {
        var page = mutedAuthors.get(userId(principal), limit, cursor);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate())
                .body(BaseResponse.<MusicianFeedMutedAuthorsResponse>builder()
                        .success(true).message("Sessize alınan profiller getirildi.").code(200).data(page).build());
    }

    @PostMapping("/items/{itemId}/feedback")
    public ResponseEntity<BaseResponse<MusicianFeedFeedbackResponse>> record(
            @AuthenticationPrincipal UserDetailsImpl principal,
            @PathVariable String itemId,
            @Valid @RequestBody MusicianFeedFeedbackRequest request
    ) {
        UUID viewerId = userId(principal);
        rateLimitGuard.checkFeedback(viewerId);
        var value = service.recordItem(viewerId, itemId, request);
        return ok(value, "Akış tercihi kaydedildi.");
    }

    @PutMapping("/authors/{profileType}/{profileId}/mute")
    public ResponseEntity<BaseResponse<MusicianFeedFeedbackResponse>> mute(
            @AuthenticationPrincipal UserDetailsImpl principal,
            @PathVariable String profileType,
            @PathVariable UUID profileId
    ) {
        UUID viewerId = userId(principal);
        rateLimitGuard.checkFeedback(viewerId);
        return ok(service.mute(viewerId, profileType, profileId), "Profil akışta sessize alındı.");
    }

    @DeleteMapping("/authors/{profileType}/{profileId}/mute")
    public ResponseEntity<BaseResponse<Void>> unmute(
            @AuthenticationPrincipal UserDetailsImpl principal,
            @PathVariable String profileType,
            @PathVariable UUID profileId
    ) {
        UUID viewerId = userId(principal);
        rateLimitGuard.checkFeedback(viewerId);
        service.unmute(viewerId, profileType, profileId);
        return ok(null, "Profilin akış sessizi kaldırıldı.");
    }

    private UUID userId(UserDetailsImpl principal) {
        if (principal == null) throw new SoundConnectException(ErrorType.UNAUTHORIZED);
        return principal.getId();
    }

    private <T> ResponseEntity<BaseResponse<T>> ok(T data, String message) {
        return ResponseEntity.ok(BaseResponse.<T>builder()
                .success(true).message(message).code(200).data(data).build());
    }
}
