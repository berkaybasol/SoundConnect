package com.berkayb.soundconnect.modules.analytics;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.event.support.EventScheduleClock;
import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileType;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/feed/announcements")
public class AnnouncementAnalyticsController {
    private final AnnouncementAnalyticsStore store;
    private final AnalyticsRateGuard rate;
    private final EventScheduleClock clock;

    @GetMapping("/{id}/statistics")
    @PreAuthorize("hasAuthority('MANAGE_PROMOTIONS')")
    public ResponseEntity<BaseResponse<AnnouncementAnalyticsResponse.Summary>> summary(
            @AuthenticationPrincipal UserDetailsImpl principal, @PathVariable UUID id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) ProfileType profileType,
            @RequestParam(required = false) AnalyticsRequest.Source source, HttpServletRequest request) {
        if (principal == null) throw new SoundConnectException(ErrorType.UNAUTHORIZED);
        rate.read(request, principal.getId());
        var value = AnalyticsService.storage(() ->
                store.summary(principal.getId(), id, from, to, profileType, source, clock.instant()));
        return ResponseEntity.ok().header("Cache-Control", "private, no-store").body(
                BaseResponse.<AnnouncementAnalyticsResponse.Summary>builder().success(true).code(200)
                        .message("Duyuru istatistikleri getirildi.").data(value).build());
    }
}
