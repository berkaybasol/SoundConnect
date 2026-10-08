package com.berkayb.soundconnect.modules.event.management;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/venue-owner/events/venue/{venueId}")
@PreAuthorize("hasRole('VENUE')")
public class EventManagementController {
    private final EventManagementService service;

    @GetMapping("/management")
    public BaseResponse<EventManagementResponse> management(@AuthenticationPrincipal UserDetailsImpl principal,
            @PathVariable UUID venueId, HttpServletResponse response) {
        response.setHeader("Cache-Control", "private, no-store");
        return BaseResponse.<EventManagementResponse>builder().success(true).code(200)
                .data(service.management(principal.getId(), venueId)).build();
    }

    @GetMapping("/history")
    public BaseResponse<EventHistoryPage> history(@AuthenticationPrincipal UserDetailsImpl principal,
            @PathVariable UUID venueId, @RequestParam String asOf,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int size,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "private, no-store");
        return BaseResponse.<EventHistoryPage>builder().success(true).code(200)
                .data(service.history(principal.getId(), venueId, asOf, cursor, size)).build();
    }
}
