package com.berkayb.soundconnect.modules.collab.controller.admin;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.collab.dto.request.CollabReportReviewRequest;
import com.berkayb.soundconnect.modules.collab.dto.response.CollabReportAdminResponse;
import com.berkayb.soundconnect.modules.collab.enums.CollabReportReason;
import com.berkayb.soundconnect.modules.collab.enums.CollabReportStatus;
import com.berkayb.soundconnect.modules.collab.service.CollabReportModerationService;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import com.berkayb.soundconnect.shared.response.PageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static com.berkayb.soundconnect.shared.constant.EndPoints.CollabAdmin.BASE;
import static com.berkayb.soundconnect.shared.constant.EndPoints.CollabAdmin.REVIEW;

@RestController
@RequiredArgsConstructor
@RequestMapping(BASE)
@PreAuthorize("hasAuthority('MANAGE_COLLAB_REPORTS')")
public class CollabReportAdminController {
    private final CollabReportModerationService service;

    @GetMapping
    public ResponseEntity<BaseResponse<PageResponse<CollabReportAdminResponse>>> list(
            @RequestParam(required = false) CollabReportStatus status,
            @RequestParam(required = false) CollabReportReason reason,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(BaseResponse.<PageResponse<CollabReportAdminResponse>>builder()
                .success(true).code(200).message("Collab raporları listelendi.")
                .data(service.list(status, reason, page, size)).build());
    }

    @PostMapping(REVIEW)
    public ResponseEntity<BaseResponse<CollabReportAdminResponse>> review(
            @AuthenticationPrincipal UserDetailsImpl principal,
            @PathVariable UUID reportId,
            @Valid @RequestBody CollabReportReviewRequest request) {
        if (principal == null) {
            throw new SoundConnectException(ErrorType.UNAUTHORIZED);
        }
        return ResponseEntity.ok(BaseResponse.<CollabReportAdminResponse>builder()
                .success(true).code(200).message("Collab raporu sonuçlandırıldı.")
                .data(service.review(principal.getId(), reportId, request)).build());
    }
}
