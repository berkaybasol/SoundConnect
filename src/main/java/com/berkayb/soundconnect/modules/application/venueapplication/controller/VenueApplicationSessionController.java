package com.berkayb.soundconnect.modules.application.venueapplication.controller;

import com.berkayb.soundconnect.auth.dto.response.LoginResponse;
import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.application.venueapplication.dto.response.VenueApplicationDecisionDto;
import com.berkayb.soundconnect.modules.application.venueapplication.service.VenueApplicationSessionService;
import com.berkayb.soundconnect.modules.notification.dto.request.NotificationDeliveryStateRequest;
import com.berkayb.soundconnect.modules.notification.dto.response.NotificationDeliveryStateResponse;
import com.berkayb.soundconnect.modules.notification.dto.response.NotificationResponseDto;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping(VenueApplicationSessionController.BASE)
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class VenueApplicationSessionController {
    public static final String BASE="/api/v1/venue-application-session/applications/{applicationId}";
    @ModelAttribute
    public void noStore(jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");
    }
    private final VenueApplicationSessionService sessions;

    @GetMapping
    public BaseResponse<VenueApplicationDecisionDto> detail(@AuthenticationPrincipal UserDetailsImpl user,
            @PathVariable UUID applicationId) { return response(sessions.detail(user.getId(),applicationId)); }
    @PostMapping("/promote")
    public BaseResponse<LoginResponse> promote(@AuthenticationPrincipal UserDetailsImpl user,
            @PathVariable UUID applicationId) {
        return response(sessions.promote(user.getId(),applicationId,user.getUser().getSessionVersion()));
    }
    @GetMapping("/notifications/{notificationId}")
    public BaseResponse<NotificationResponseDto> notification(@AuthenticationPrincipal UserDetailsImpl user,
            @PathVariable UUID applicationId,@PathVariable UUID notificationId) {
        return response(sessions.notification(user.getId(),applicationId,notificationId));
    }
    @PostMapping("/notifications/{notificationId}/read")
    public BaseResponse<Void> read(@AuthenticationPrincipal UserDetailsImpl user,
            @PathVariable UUID applicationId,@PathVariable UUID notificationId) {
        sessions.read(user.getId(),applicationId,notificationId); return response(null);
    }
    @PostMapping("/notifications/delivery-state")
    public BaseResponse<NotificationDeliveryStateResponse> dismissed(@AuthenticationPrincipal UserDetailsImpl user,
            @PathVariable UUID applicationId,@Valid @RequestBody NotificationDeliveryStateRequest request) {
        return response(new NotificationDeliveryStateResponse(sessions.dismissed(user.getId(),applicationId,request.notificationIds())));
    }
    static <T> BaseResponse<T> response(T value) {
        return BaseResponse.<T>builder().success(true).code(200).message("Application state fetched").data(value).build();
    }
}
