package com.berkayb.soundconnect.modules.admin.health;

import com.berkayb.soundconnect.shared.response.BaseResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController @RequestMapping("/api/v1/admin/system-health/mobile-events")
@RequiredArgsConstructor @Validated
public class MobileDiagnosticsAdminController {
    public record RecentEvents(List<MobileDiagnosticsStore.Event> events) { }
    private final MobileDiagnosticsStore store;

    @GetMapping @PreAuthorize("hasAuthority('ADMIN_PANEL_ACCESS')")
    public ResponseEntity<BaseResponse<RecentEvents>> recent(@RequestParam(defaultValue = "10") @Min(1) @Max(10) int limit) {
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(BaseResponse.<RecentEvents>builder()
                    .success(true).code(200).message("Recent mobile diagnostic codes fetched")
                    .data(new RecentEvents(store.recent(limit))).build());
        } catch (RuntimeException unavailable) {
            return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).header("Retry-After", "5")
                    .body(BaseResponse.<RecentEvents>builder().success(false).code(503).message("DIAGNOSTICS_RETRY_LATER").build());
        }
    }
}
