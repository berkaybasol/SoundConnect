package com.berkayb.soundconnect.modules.contentreport;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.admin.security.AdminPanelReadAccess;
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
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ContentReportController {
    private static final String STAFF = AdminPanelReadAccess.PANEL + " and hasAuthority('MANAGE_CONTENT_REPORTS')";
    private final ContentReportService reports;
    private final ContentReportModeration moderation;

    @PostMapping("/content-reports")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<BaseResponse<ContentReportService.Receipt>> create(@AuthenticationPrincipal UserDetailsImpl actor,
            @RequestBody ContentReportRequest request) {
        return result(reports.create(actor, request));
    }

    @GetMapping("/admin/content-reports")
    @PreAuthorize(STAFF)
    public ResponseEntity<BaseResponse<PageResponse<ContentReportModeration.Summary>>> list(
            @AuthenticationPrincipal UserDetailsImpl actor,
            @RequestParam(defaultValue = "OPEN") ContentReportModeration.Status status,
            @RequestParam(required = false) ContentReportKind kind,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return result(moderation.list(actor, status, kind, page, size));
    }

    @GetMapping("/admin/content-reports/{id}")
    @PreAuthorize(STAFF)
    public ResponseEntity<BaseResponse<ContentReportModeration.Detail>> detail(@AuthenticationPrincipal UserDetailsImpl actor,
            @PathVariable UUID id) {
        return result(moderation.detail(actor, id));
    }

    @PostMapping("/admin/content-reports/{id}/review")
    @PreAuthorize(STAFF)
    public ResponseEntity<BaseResponse<ContentReportModeration.Detail>> review(@AuthenticationPrincipal UserDetailsImpl actor,
            @PathVariable UUID id, @Valid @RequestBody ContentReportModeration.Review review) {
        return result(moderation.review(actor, id, review));
    }

    private static <T> ResponseEntity<BaseResponse<T>> result(T data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate())
                .body(BaseResponse.<T>builder().success(true).code(200).data(data).build());
    }
}
