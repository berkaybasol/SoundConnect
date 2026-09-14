package com.berkayb.soundconnect.modules.feed.studio.api;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.feed.musician.abuse.MusicianFeedRateLimitGuard;
import com.berkayb.soundconnect.modules.feed.musician.api.*;
import com.berkayb.soundconnect.modules.feed.musician.core.MusicianFeedService;
import com.berkayb.soundconnect.modules.feed.musician.delivery.MusicianFeedTelemetryService;
import com.berkayb.soundconnect.modules.feed.musician.feedback.MusicianFeedFeedbackService;
import com.berkayb.soundconnect.modules.feed.musician.feedback.MusicianFeedMutedAuthorsService;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Studio audience boundary over the shared feed, delivery and moderation engine. */
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.feed.musician", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequestMapping("/api/v1/feed/studio")
@PreAuthorize("hasRole('STUDIO') and !hasRole('LISTENER')")
public class StudioFeedController {
    private final MusicianFeedService feed;
    private final MusicianFeedFeedbackService feedback;
    private final MusicianFeedMutedAuthorsService mutedAuthors;
    private final MusicianFeedTelemetryService telemetry;
    private final MusicianFeedRateLimitGuard rateLimitGuard;

    @GetMapping
    public ResponseEntity<BaseResponse<MusicianFeedPageResponse>> get(
            @AuthenticationPrincipal UserDetailsImpl principal,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor,
            @RequestParam List<String> supportedItemTypes) {
        UUID viewerId = userId(principal);
        rateLimitGuard.checkPage(viewerId, cursor != null && !cursor.isBlank());
        return privatePage(feed.getForStudio(viewerId, limit, cursor, supportedItemTypes),
                "Stüdyo akışı getirildi.");
    }

    @GetMapping("/muted-authors")
    public ResponseEntity<BaseResponse<MusicianFeedMutedAuthorsResponse>> mutedAuthors(
            @AuthenticationPrincipal UserDetailsImpl principal,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        return privatePage(mutedAuthors.getForStudio(userId(principal), limit, cursor),
                "Sessize alınan profiller getirildi.");
    }

    @PostMapping("/items/{itemId}/feedback")
    public ResponseEntity<BaseResponse<MusicianFeedFeedbackResponse>> feedback(
            @AuthenticationPrincipal UserDetailsImpl principal,
            @PathVariable String itemId,
            @Valid @RequestBody MusicianFeedFeedbackRequest request) {
        UUID viewerId = userId(principal);
        rateLimitGuard.checkFeedback(viewerId);
        return ok(feedback.recordItemForStudio(viewerId, itemId, request), "Akış tercihi kaydedildi.");
    }

    @PutMapping("/authors/{profileType}/{profileId}/mute")
    public ResponseEntity<BaseResponse<MusicianFeedFeedbackResponse>> mute(
            @AuthenticationPrincipal UserDetailsImpl principal,
            @PathVariable String profileType,
            @PathVariable UUID profileId) {
        UUID viewerId = userId(principal);
        rateLimitGuard.checkFeedback(viewerId);
        return ok(feedback.muteForStudio(viewerId, profileType, profileId), "Profil akışta sessize alındı.");
    }

    @DeleteMapping("/authors/{profileType}/{profileId}/mute")
    public ResponseEntity<BaseResponse<Void>> unmute(
            @AuthenticationPrincipal UserDetailsImpl principal,
            @PathVariable String profileType,
            @PathVariable UUID profileId) {
        UUID viewerId = userId(principal);
        rateLimitGuard.checkFeedback(viewerId);
        feedback.unmuteForStudio(viewerId, profileType, profileId);
        return ok(null, "Profilin akış sessizi kaldırıldı.");
    }

    @PostMapping("/events")
    public ResponseEntity<BaseResponse<MusicianFeedTelemetryResponse>> recordEvent(
            @AuthenticationPrincipal UserDetailsImpl principal,
            @Valid @RequestBody MusicianFeedTelemetryRequest request) {
        UUID viewerId = userId(principal);
        rateLimitGuard.checkTelemetry(viewerId);
        return ok(telemetry.recordForStudio(viewerId, request), "Akış olayı kaydedildi.");
    }

    private UUID userId(UserDetailsImpl principal) {
        if (principal == null) throw new SoundConnectException(ErrorType.UNAUTHORIZED);
        return principal.getId();
    }

    private <T> ResponseEntity<BaseResponse<T>> privatePage(T value, String message) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate())
                .body(envelope(value, message));
    }

    private <T> ResponseEntity<BaseResponse<T>> ok(T value, String message) {
        return ResponseEntity.ok(envelope(value, message));
    }

    private <T> BaseResponse<T> envelope(T value, String message) {
        return BaseResponse.<T>builder().success(true).message(message).code(200).data(value).build();
    }
}
