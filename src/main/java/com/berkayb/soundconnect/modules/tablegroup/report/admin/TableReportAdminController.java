package com.berkayb.soundconnect.modules.tablegroup.report.admin;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.admin.security.AdminPanelReadAccess;
import com.berkayb.soundconnect.modules.tablegroup.report.TableGroupReportRequest.Kind;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import com.berkayb.soundconnect.shared.response.PageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/table-group-reports")
@PreAuthorize(AdminPanelReadAccess.PANEL + " and hasAuthority('MANAGE_TABLE_GROUP_REPORTS')")
public class TableReportAdminController {
    private final TableReportModeration moderation;

    @GetMapping
    public ResponseEntity<BaseResponse<PageResponse<TableReportModeration.Summary>>> list(
            @AuthenticationPrincipal UserDetailsImpl actor,
            @RequestParam(defaultValue = "OPEN") TableReportModeration.Status status,
            @RequestParam(required = false) Kind kind,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return result("Masa şikâyetleri listelendi.", moderation.list(actor, status, kind, page, size));
    }

    @GetMapping("/{reportId}")
    public ResponseEntity<BaseResponse<TableReportModeration.Detail>> detail(
            @AuthenticationPrincipal UserDetailsImpl actor, @PathVariable UUID reportId) {
        return result("Masa şikâyeti getirildi.", moderation.detail(actor, reportId));
    }

    @PostMapping("/{reportId}/review")
    public ResponseEntity<BaseResponse<TableReportModeration.Detail>> review(
            @AuthenticationPrincipal UserDetailsImpl actor, @PathVariable UUID reportId,
            @Valid @RequestBody TableReportModeration.Review request) {
        return result("Masa şikâyeti sonuçlandırıldı.", moderation.review(actor, reportId, request));
    }

    private static <T> ResponseEntity<BaseResponse<T>> result(String message, T data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate())
                .body(BaseResponse.<T>builder().success(true).code(200).message(message).data(data).build());
    }
}
