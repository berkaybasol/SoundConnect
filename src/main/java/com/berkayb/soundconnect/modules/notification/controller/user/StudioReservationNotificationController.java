package com.berkayb.soundconnect.modules.notification.controller.user;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.notification.dto.response.StudioReservationNotificationTargetResponse;
import com.berkayb.soundconnect.modules.notification.service.StudioReservationNotificationTargetService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
import static com.berkayb.soundconnect.shared.constant.EndPoints.Notification.USER_BASE;

@RestController
@RequestMapping(USER_BASE)
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated() and !hasRole('LISTENER')")
public class StudioReservationNotificationController {
    private final StudioReservationNotificationTargetService targets;

    @GetMapping("/{notificationId}/studio-reservation")
    public BaseResponse<StudioReservationNotificationTargetResponse> reservation(
            @AuthenticationPrincipal UserDetailsImpl principal, @PathVariable UUID notificationId) {
        return BaseResponse.<StudioReservationNotificationTargetResponse>builder().success(true).code(200)
                .data(targets.resolve(principal==null ? null : principal.getId(), notificationId)).build();
    }
}
