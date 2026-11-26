package com.berkayb.soundconnect.modules.user.controller;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.user.service.MemberAnalyticsPreferenceService;
import com.berkayb.soundconnect.modules.user.service.MemberAnalyticsPreferenceService.FirstAction;
import com.berkayb.soundconnect.shared.constant.EndPoints;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class MemberFirstActionController {
    private final MemberAnalyticsPreferenceService preferences;

    public record Claim(UUID analyticsUserId) {}

    @PostMapping(EndPoints.User.BASE + "/me/analytics-first-action/claim")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<BaseResponse<FirstAction>> claim(@AuthenticationPrincipal UserDetailsImpl actor, @RequestBody Claim claim) {
        var action = preferences.claimFirstAction(actor, claim.analyticsUserId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate())
                .body(BaseResponse.<FirstAction>builder().success(true).code(200).data(action).build());
    }
}
