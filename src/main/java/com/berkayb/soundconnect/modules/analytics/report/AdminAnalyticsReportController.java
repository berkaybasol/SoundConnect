package com.berkayb.soundconnect.modules.analytics.report;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.admin.security.AdminPanelReadAccess;
import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileType;
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
@RequestMapping(EndPoints.API + EndPoints.VERSION + "/admin/users/analytics-report")
@PreAuthorize(AdminPanelReadAccess.PANEL + " and hasAuthority('READ_USERS')")
public class AdminAnalyticsReportController {
    private final AdminAnalyticsReports reports;
    @GetMapping
    public ResponseEntity<BaseResponse<AnalyticsReport>> report(@AuthenticationPrincipal UserDetailsImpl actor,
            @RequestParam String month, @RequestParam(required=false) ProfileType profileType) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate()).body(BaseResponse.<AnalyticsReport>builder()
                .success(true).code(200).data(reports.get(actor,month,profileType)).build());
    }
}
