package com.berkayb.soundconnect.modules.tablegroup.report;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.shared.constant.EndPoints;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import com.berkayb.soundconnect.shared.response.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping(EndPoints.TableGroup.BASE)
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class TableGroupReportController {
    private final TableGroupReportService reports;
    private final RecentReportableTables recent;

    @PostMapping("/{tableGroupId}/reports")
    public ResponseEntity<BaseResponse<TableGroupReportService.Receipt>> create(
            @AuthenticationPrincipal UserDetailsImpl actor, @PathVariable UUID tableGroupId,
            @RequestBody TableGroupReportRequest request) {
        return response(reports.create(actor.getId(), tableGroupId, request));
    }

    @GetMapping("/reportable-recent")
    public ResponseEntity<BaseResponse<PageResponse<RecentReportableTables.Row>>> recent(
            @AuthenticationPrincipal UserDetailsImpl actor, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return response(recent.list(actor.getId(), page, size));
    }

    private static <T> ResponseEntity<BaseResponse<T>> response(T data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate())
                .body(BaseResponse.<T>builder().success(true).code(200).data(data).build());
    }
}
