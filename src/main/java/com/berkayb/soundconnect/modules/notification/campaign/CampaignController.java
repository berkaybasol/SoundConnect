package com.berkayb.soundconnect.modules.notification.campaign;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static com.berkayb.soundconnect.modules.notification.campaign.CampaignContract.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/notifications/campaigns")
@PreAuthorize("!hasRole('LISTENER') and hasAnyRole('OWNER','ADMIN')")
public class CampaignController {
    private final CampaignService service;
    private final CampaignAccess access;
    private final CampaignTargets targets;

    @GetMapping
    public BaseResponse<Page> list(
            @AuthenticationPrincipal UserDetailsImpl user,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return response(service.list(user.getId(), page, size));
    }

    @GetMapping("/{id}")
    public BaseResponse<Campaign> get(
            @AuthenticationPrincipal UserDetailsImpl user, @PathVariable UUID id) {
        return response(service.get(user.getId(), id));
    }

    @PostMapping
    public BaseResponse<Campaign> create(
            @AuthenticationPrincipal UserDetailsImpl user, @RequestBody Write write) {
        return response(service.create(user.getId(), write));
    }

    @PutMapping("/{id}")
    public BaseResponse<Campaign> update(
            @AuthenticationPrincipal UserDetailsImpl user,
            @PathVariable UUID id,
            @RequestBody Write write) {
        return response(service.update(user.getId(), id, write));
    }

    @PostMapping("/{id}/{action:schedule|pause|resume|cancel}")
    public BaseResponse<Campaign> action(
            @AuthenticationPrincipal UserDetailsImpl user,
            @PathVariable UUID id,
            @PathVariable String action,
            @RequestBody Version version) {
        return response(service.action(user.getId(), id, action, version));
    }

    @GetMapping("/users")
    public BaseResponse<List<UserOption>> users(
            @AuthenticationPrincipal UserDetailsImpl user, @RequestParam String q) {
        return response(access.search(user.getId(), q));
    }

    @GetMapping("/targets")
    public BaseResponse<List<TargetOption>> targets(
            @AuthenticationPrincipal UserDetailsImpl user,
            @RequestParam Kind kind,
            @RequestParam String q) {
        return response(targets.search(user.getId(), kind, q));
    }

    static <T> BaseResponse<T> response(T value) {
        return BaseResponse.<T>builder()
                .success(true)
                .code(200)
                .message("Özel bildirim")
                .data(value)
                .build();
    }
}
