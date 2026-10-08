package com.berkayb.soundconnect.modules.notification.campaign;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class CustomNotificationController {
    private final CampaignTargets targets;

    @GetMapping({
            "/api/v1/user/notifications/{id}/custom-target",
            "/api/v1/notifications/{id}/custom-target"
    })
    public BaseResponse<CampaignContract.Resolved> resolve(
            @AuthenticationPrincipal UserDetailsImpl user, @PathVariable UUID id) {
        return CampaignController.response(targets.resolve(user.getId(), id));
    }
}
