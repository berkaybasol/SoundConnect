package com.berkayb.soundconnect.modules.analytics;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController @RequiredArgsConstructor
public class AnalyticsController {
    private final AnalyticsService service;
    private final AnalyticsRateGuard guard;

    @PostMapping("/api/v1/analytics/observations")
    public BaseResponse<AnalyticsResponse.Acknowledgement> observe(@AuthenticationPrincipal UserDetailsImpl principal,
            @RequestBody AnalyticsRequest request, HttpServletRequest httpRequest) {
        // Invalid/disabled bearer sessions must not silently become an anonymous installation.
        if (principal == null && httpRequest.getHeader("Authorization") != null) throw new SoundConnectException(ErrorType.UNAUTHORIZED);
        return response(service.observe(principal == null ? null : principal.getId(), request));
    }
    @GetMapping("/api/v1/venue-analytics/{venueId}") @PreAuthorize("isAuthenticated()")
    public ResponseEntity<BaseResponse<AnalyticsResponse.Summary>> summary(@AuthenticationPrincipal UserDetailsImpl principal,
            @PathVariable UUID venueId, @RequestParam(defaultValue = "30") int days, HttpServletRequest request, HttpServletResponse response) {
        UUID owner = reportingOwner(principal, response); guard.read(request, owner);
        return privateResponse(service.summary(owner, venueId, null, days));
    }
    @GetMapping("/api/v1/venue-analytics/{venueId}/events") @PreAuthorize("isAuthenticated()")
    public ResponseEntity<BaseResponse<AnalyticsResponse.EventPage>> events(@AuthenticationPrincipal UserDetailsImpl principal,
            @PathVariable UUID venueId, @RequestParam(defaultValue = "30") int days,
            @RequestParam(defaultValue = "0") @Min(0) @Max(1000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size,
            @RequestParam(defaultValue = "DATE") AnalyticsResponse.EventSort sort, HttpServletRequest request, HttpServletResponse response) {
        UUID owner = reportingOwner(principal, response); guard.read(request, owner);
        return privateResponse(service.events(owner, venueId, days, page, size, sort));
    }
    @GetMapping("/api/v1/venue-analytics/{venueId}/events/{eventId}") @PreAuthorize("isAuthenticated()")
    public ResponseEntity<BaseResponse<AnalyticsResponse.Summary>> event(@AuthenticationPrincipal UserDetailsImpl principal,
            @PathVariable UUID venueId, @PathVariable UUID eventId,
            @RequestParam(defaultValue = "30") int days, HttpServletRequest request, HttpServletResponse response) {
        UUID owner = reportingOwner(principal, response); guard.read(request, owner);
        return privateResponse(service.summary(owner, venueId, eventId, days));
    }
    private UUID reportingOwner(UserDetailsImpl principal, HttpServletResponse response) {
        response.setHeader("Cache-Control", "private, no-store");
        UUID owner = owner(principal);
        service.requireReportingEnabled();
        return owner;
    }
    private UUID owner(UserDetailsImpl principal) {
        if (principal == null) throw new SoundConnectException(ErrorType.UNAUTHORIZED);
        return principal.getId();
    }
    private <T> BaseResponse<T> response(T data) { return BaseResponse.<T>builder().success(true).code(200).message("İşlem tamamlandı.").data(data).build(); }
    private <T> ResponseEntity<BaseResponse<T>> privateResponse(T data) {
        return ResponseEntity.ok().header("Cache-Control", "private, no-store").body(response(data));
    }
}
