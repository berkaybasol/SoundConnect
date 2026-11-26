package com.berkayb.soundconnect.modules.analytics.report;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.analytics.AnalyticsRateGuard;
import com.berkayb.soundconnect.modules.user.service.MemberAnalyticsPreferenceService;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@ConditionalOnProperty(name = "app.analytics-report.live-enabled", havingValue = "true")
@RequestMapping("/api/v1/analytics/live-activity")
@RequiredArgsConstructor
public class LiveActivityController {
    public record GuestVisit(UUID installationId, String policyVersion) {}
    public record MemberVisit(UUID analyticsUserId, String policyVersion) {}
    private final LiveActivityStore store;
    private final AnalyticsRateGuard limits;

    @PostMapping("/guest")
    @PreAuthorize("isAnonymous()")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void guest(@RequestBody GuestVisit visit, HttpServletRequest request) {
        if (request.getHeader("Authorization") != null || !randomId(visit.installationId())) {
            throw new SoundConnectException(ErrorType.VALIDATION_ERROR);
        }
        if (MemberAnalyticsPreferenceService.POLICY_VERSION.equals(visit.policyVersion())) {
            limits.liveActivity(request);
            store.guest(visit.installationId());
        }
    }

    @PostMapping("/member")
    @PreAuthorize("isAuthenticated() and !hasAnyAuthority('ROLE_ADMIN','ROLE_OWNER','ADMIN_PANEL_ACCESS')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void member(@AuthenticationPrincipal UserDetailsImpl actor, @RequestBody MemberVisit visit, HttpServletRequest request) {
        if (!randomId(visit.analyticsUserId())) throw new SoundConnectException(ErrorType.VALIDATION_ERROR);
        if (MemberAnalyticsPreferenceService.POLICY_VERSION.equals(visit.policyVersion())) {
            limits.liveActivity(request);
            store.member(actor, visit.analyticsUserId());
        }
    }

    private static boolean randomId(UUID id) {
        return id != null && id.version() == 4 && id.variant() == 2;
    }
}
