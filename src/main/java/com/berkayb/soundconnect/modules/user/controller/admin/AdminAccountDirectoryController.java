package com.berkayb.soundconnect.modules.user.controller.admin;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.admin.security.AdminPanelReadAccess;
import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileType;
import com.berkayb.soundconnect.modules.user.dto.response.AdminAccountDetail;
import com.berkayb.soundconnect.modules.user.dto.response.AdminAccountSummary;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.user.service.AdminAccountDirectory;
import com.berkayb.soundconnect.modules.user.service.AdminAccountAccessService;
import com.berkayb.soundconnect.shared.constant.EndPoints;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import com.berkayb.soundconnect.shared.response.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping(EndPoints.API + EndPoints.VERSION + "/admin/users")
@PreAuthorize(AdminPanelReadAccess.PANEL + " and hasAuthority('READ_USERS')")
public class AdminAccountDirectoryController {
    private final AdminAccountDirectory accounts;
    private final AdminAccountAccessService access;

    @GetMapping
    public ResponseEntity<BaseResponse<PageResponse<AdminAccountSummary>>> list(
            @AuthenticationPrincipal UserDetailsImpl actor, @RequestParam(required = false) String query,
            @RequestParam(required = false) ProfileType profileType, @RequestParam(required = false) UserStatus status,
            @RequestParam(required = false) Boolean suspended,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return response(accounts.list(actor, query, profileType, status, suspended, page, size));
    }

    @GetMapping("/analytics-preferences")
    public ResponseEntity<BaseResponse<AdminAccountDirectory.AnalyticsPreferenceCounts>> analyticsPreferences(
            @AuthenticationPrincipal UserDetailsImpl actor, @RequestParam(required = false) ProfileType profileType) {
        return response(accounts.analyticsPreferences(actor, profileType));
    }

    @GetMapping("/{userId}")
    public ResponseEntity<BaseResponse<AdminAccountDetail>> detail(@AuthenticationPrincipal UserDetailsImpl actor,
                                                                   @PathVariable UUID userId) {
        return response(accounts.detail(actor, userId));
    }

    @PostMapping("/{userId}/access")
    @PreAuthorize(AdminPanelReadAccess.PANEL + " and hasAuthority('MANAGE_USERS')")
    public ResponseEntity<BaseResponse<AdminAccountAccessService.DecisionReceipt>> decide(
            @AuthenticationPrincipal UserDetailsImpl actor, @PathVariable UUID userId,
            @RequestBody AdminAccountAccessService.DecisionRequest request) {
        return response(access.decide(actor, userId, request));
    }

    private static <T> ResponseEntity<BaseResponse<T>> response(T data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate())
                .body(BaseResponse.<T>builder().success(true).code(200).data(data).build());
    }
}
