package com.berkayb.soundconnect.modules.admin.health;

import com.berkayb.soundconnect.shared.response.BaseResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/system-health")
@RequiredArgsConstructor
public class SystemHealthController {
    private final SystemHealthService service;

    @GetMapping
    @PreAuthorize("hasAuthority('ADMIN_PANEL_ACCESS')")
    public ResponseEntity<BaseResponse<SystemHealthSnapshot>> summary() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(BaseResponse.<SystemHealthSnapshot>builder().success(true).code(200)
                        .message("System health snapshot fetched").data(service.snapshot()).build());
    }
}
