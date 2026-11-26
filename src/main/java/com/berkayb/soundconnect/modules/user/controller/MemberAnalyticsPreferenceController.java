package com.berkayb.soundconnect.modules.user.controller;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.user.service.MemberAnalyticsPreferenceService;
import com.berkayb.soundconnect.modules.user.service.MemberAnalyticsPreferenceService.Change;
import com.berkayb.soundconnect.modules.user.service.MemberAnalyticsPreferenceService.Preference;
import com.berkayb.soundconnect.shared.constant.EndPoints;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping(EndPoints.User.BASE + "/me/analytics-preference")
@PreAuthorize("isAuthenticated()")
public class MemberAnalyticsPreferenceController {
    private final MemberAnalyticsPreferenceService preferences;

    @GetMapping
    public ResponseEntity<BaseResponse<Preference>> get(@AuthenticationPrincipal UserDetailsImpl actor) {
        return response(preferences.get(actor));
    }

    @PutMapping
    public ResponseEntity<BaseResponse<Preference>> change(@AuthenticationPrincipal UserDetailsImpl actor, @RequestBody Change change) {
        return response(preferences.change(actor, change));
    }

    private static ResponseEntity<BaseResponse<Preference>> response(Preference preference) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate())
                .body(BaseResponse.<Preference>builder().success(true).code(200).data(preference).build());
    }
}
