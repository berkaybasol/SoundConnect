package com.berkayb.soundconnect.modules.promotion.announcement;

import com.berkayb.soundconnect.shared.response.BaseResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/announcements")
@PreAuthorize("isAuthenticated()")
public class AnnouncementController {
    private final AnnouncementReadService service;

    @GetMapping
    public ResponseEntity<BaseResponse<AnnouncementPage>> list(@AuthenticationPrincipal(expression = "id") UUID viewer,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit) {
        return response(service.directory(viewer, cursor, limit));
    }

    @GetMapping("/{id}")
    public ResponseEntity<BaseResponse<AnnouncementResponse>> get(@AuthenticationPrincipal(expression = "id") UUID viewer,
            @PathVariable UUID id) { return response(service.get(viewer, id)); }

    private static <T> ResponseEntity<BaseResponse<T>> response(T data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate())
                .body(BaseResponse.<T>builder().success(true).code(200).message("Duyurular getirildi.").data(data).build());
    }
}
