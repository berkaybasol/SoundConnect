package com.berkayb.soundconnect.modules.analytics.report;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.shared.constant.EndPoints;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping(EndPoints.User.BASE + "/me/analytics-first-week-duration")
public class FirstWeekDurationController {
    private final FirstWeekDuration service;
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<BaseResponse<FirstWeekDuration.Receipt>> duration(@AuthenticationPrincipal UserDetailsImpl actor,
            @RequestBody FirstWeekDuration.Sample sample) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(BaseResponse.<FirstWeekDuration.Receipt>builder()
                .success(true).code(200).data(service.add(actor,sample)).build());
    }
}
