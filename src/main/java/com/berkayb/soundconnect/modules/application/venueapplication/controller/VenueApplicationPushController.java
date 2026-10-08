package com.berkayb.soundconnect.modules.application.venueapplication.controller;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.notification.push.PushDeviceRateLimit;
import com.berkayb.soundconnect.modules.notification.push.PushDeviceService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping(VenueApplicationSessionController.BASE+"/push")
@RequiredArgsConstructor
@Validated
@PreAuthorize("isAuthenticated()")
@ConditionalOnProperty(name="app.notification.push.enabled",havingValue="true")
public class VenueApplicationPushController {
    @ModelAttribute
    public void noStore(jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");
    }
    private final PushDeviceService devices;
    private final PushDeviceRateLimit limit;

    @PutMapping("/devices/{installationId}")
    public BaseResponse<Void> register(@AuthenticationPrincipal UserDetailsImpl user,@PathVariable UUID applicationId,
            @PathVariable UUID installationId,@Valid @RequestBody PushDeviceService.Registration request) {
        limit.check(user.getId()); devices.registerApplication(user.getId(),applicationId,installationId,request);
        return VenueApplicationSessionController.response(null);
    }
    @DeleteMapping("/devices/{installationId}")
    public BaseResponse<Void> revoke(@AuthenticationPrincipal UserDetailsImpl user,@PathVariable UUID applicationId,
            @PathVariable UUID installationId,@RequestParam @Min(1) @Max(PushDeviceService.MAX_CLIENT_REVISION) Long clientRevision) {
        limit.check(user.getId()); devices.revokeApplication(user.getId(),applicationId,installationId,clientRevision);
        return VenueApplicationSessionController.response(null);
    }
}
