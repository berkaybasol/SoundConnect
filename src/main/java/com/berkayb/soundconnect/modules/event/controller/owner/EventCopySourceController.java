package com.berkayb.soundconnect.modules.event.controller.owner;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.event.dto.request.EventCreateRequestDto;
import com.berkayb.soundconnect.modules.event.service.EventCopySourceService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@PreAuthorize("hasRole('VENUE')")
public class EventCopySourceController {
    private final EventCopySourceService service;

    @GetMapping("/api/v1/venue-owner/events/{eventId}/copy-source")
    public BaseResponse<EventCreateRequestDto> get(@AuthenticationPrincipal UserDetailsImpl principal,
            @PathVariable UUID eventId, HttpServletResponse response) {
        response.setHeader("Cache-Control", "private, no-store");
        return BaseResponse.<EventCreateRequestDto>builder().success(true).code(200)
                .message("Etkinlik bilgileri kopyalamak için hazır.")
                .data(service.get(principal.getId(), eventId)).build();
    }
}
