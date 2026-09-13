package com.berkayb.soundconnect.modules.promotion.announcement;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/feed/announcements")
@PreAuthorize("hasAuthority('MANAGE_PROMOTIONS')")
public class AnnouncementAdminController {
    private final AnnouncementAdminService service;

    @GetMapping
    public ResponseEntity<BaseResponse<AnnouncementPage>> list(@AuthenticationPrincipal(expression = "id") UUID actor,
            @RequestParam(required = false) AnnouncementStatus status, @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        return response(service.list(actor, status, cursor, limit));
    }

    @GetMapping("/{id}")
    public ResponseEntity<BaseResponse<AnnouncementResponse>> get(@AuthenticationPrincipal(expression = "id") UUID actor,
            @PathVariable UUID id) { return response(service.get(actor, id)); }

    @PostMapping
    public ResponseEntity<BaseResponse<AnnouncementResponse>> create(@AuthenticationPrincipal(expression = "id") UUID actor,
            @Valid @RequestBody AnnouncementWrite request) { return response(service.create(actor, request)); }

    @PutMapping("/{id}")
    public ResponseEntity<BaseResponse<AnnouncementResponse>> update(@AuthenticationPrincipal(expression = "id") UUID actor,
            @PathVariable UUID id, @Valid @RequestBody AnnouncementWrite request) { return response(service.update(actor, id, request)); }

    @PostMapping("/{id}/publish")
    public ResponseEntity<BaseResponse<AnnouncementResponse>> publish(@AuthenticationPrincipal(expression = "id") UUID actor,
            @PathVariable UUID id, @Valid @RequestBody AnnouncementPublish request) { return response(service.publish(actor, id, request)); }

    @PostMapping("/{id}/end")
    public ResponseEntity<BaseResponse<AnnouncementResponse>> end(@AuthenticationPrincipal(expression = "id") UUID actor,
            @PathVariable UUID id, @Valid @RequestBody AnnouncementVersionAction request) { return response(service.end(actor, id, request)); }

    @PostMapping("/{id}/archive")
    public ResponseEntity<BaseResponse<AnnouncementResponse>> archive(@AuthenticationPrincipal(expression = "id") UUID actor,
            @PathVariable UUID id, @Valid @RequestBody AnnouncementVersionAction request) { return response(service.archive(actor, id, request)); }

    @DeleteMapping("/{id}")
    public ResponseEntity<BaseResponse<Void>> delete(@AuthenticationPrincipal(expression = "id") UUID actor,
            @PathVariable UUID id, @RequestParam Long version) {
        service.delete(actor, id, version);
        return response(null);
    }

    private static <T> ResponseEntity<BaseResponse<T>> response(T data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate())
                .body(BaseResponse.<T>builder().success(true).code(200).message("Duyuru işlemi tamamlandı.").data(data).build());
    }
}
