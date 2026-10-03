package com.berkayb.soundconnect.modules.notification.push;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.validation.annotation.Validated;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/user/notifications/push")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
@Validated
@ConditionalOnProperty(name = "app.notification.push.enabled", havingValue = "true")
public class PushDeviceController {
    private final PushDeviceService devices;
    private final PushDeviceRateLimit rateLimit;

    @PutMapping("/devices/{installationId}")
    public BaseResponse<Void> register(@AuthenticationPrincipal UserDetailsImpl user,
            @PathVariable UUID installationId, @Valid @RequestBody PushDeviceService.Registration request) {
        rateLimit.check(user.getId());
        devices.register(user.getId(), installationId, request);
        return response(null);
    }
    @DeleteMapping("/devices/{installationId}")
    public BaseResponse<Void> revoke(@AuthenticationPrincipal UserDetailsImpl user, @PathVariable UUID installationId,
            @RequestParam @Min(1) @Max(PushDeviceService.MAX_CLIENT_REVISION) Long clientRevision) {
        rateLimit.check(user.getId());
        devices.revoke(user.getId(), installationId, clientRevision);
        return response(null);
    }
    @GetMapping("/preferences")
    public BaseResponse<PushDeviceService.Preferences> preferences(@AuthenticationPrincipal UserDetailsImpl user) {
        return response(devices.preferences(user.getId()));
    }
    @PutMapping("/preferences")
    public BaseResponse<PushDeviceService.Preferences> preferences(@AuthenticationPrincipal UserDetailsImpl user,
            @Valid @RequestBody PushDeviceService.Preferences preferences) {
        rateLimit.check(user.getId());
        return response(devices.updatePreferences(user.getId(), preferences));
    }
    private static <T> BaseResponse<T> response(T value) {
        return BaseResponse.<T>builder().success(true).code(200).message("Push settings updated").data(value).build();
    }
}
