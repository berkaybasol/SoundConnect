package com.berkayb.soundconnect.modules.feed.musician.moderation;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedItemType;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

/** Moderation remains available when musician-feed serving is disabled. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/musician-feed/reports")
@PreAuthorize("hasAuthority('MANAGE_MUSICIAN_FEED_REPORTS')")
public class MusicianFeedReportAdminController {
    private final MusicianFeedReportModerationService service;

    @GetMapping
    public ResponseEntity<BaseResponse<MusicianFeedReportPage>> list(
            @AuthenticationPrincipal UserDetailsImpl principal,
            @RequestParam(required = false) MusicianFeedReportStatus status,
            @RequestParam(required = false) MusicianFeedItemType itemType,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        return ok(service.list(viewer(principal), status, itemType, limit, cursor), "Akış raporları listelendi.");
    }

    @GetMapping("/{reportId}")
    public ResponseEntity<BaseResponse<MusicianFeedReportDetail>> detail(
            @AuthenticationPrincipal UserDetailsImpl principal, @PathVariable UUID reportId) {
        return ok(service.detail(viewer(principal), reportId), "Akış raporu getirildi.");
    }

    @PostMapping("/{reportId}/review")
    public ResponseEntity<BaseResponse<MusicianFeedReportDetail>> review(
            @AuthenticationPrincipal UserDetailsImpl principal, @PathVariable UUID reportId,
            @Valid @RequestBody MusicianFeedReportReviewRequest request) {
        return ok(service.review(viewer(principal), reportId, request), "Akış raporu güncellendi.");
    }

    private UUID viewer(UserDetailsImpl principal) {
        if (principal == null) throw new SoundConnectException(ErrorType.UNAUTHORIZED);
        return principal.getId();
    }

    private <T> ResponseEntity<BaseResponse<T>> ok(T data, String message) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate())
                .body(BaseResponse.<T>builder().success(true).code(200).message(message).data(data).build());
    }
}
