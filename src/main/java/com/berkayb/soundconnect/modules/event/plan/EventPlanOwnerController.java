package com.berkayb.soundconnect.modules.event.plan;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.shared.exception.*;
import com.berkayb.soundconnect.shared.response.*;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.UUID;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/v1/venue-owner/event-plans")
@PreAuthorize("hasRole('VENUE')")
public class EventPlanOwnerController {
    private final EventPlanService service;
    private final EventPlanRateGuard guard;
    @PostMapping("/preview")
    public ResponseEntity<BaseResponse<EventPlanPreview>> preview(@AuthenticationPrincipal UserDetailsImpl principal,
            @RequestBody EventPlanDefinition definition) {
        UUID actor=principal.getUser().getId();guard.checkPreview(actor);return response(service.preview(actor,definition));
    }
    @PostMapping("/{planId}/preview")
    public ResponseEntity<BaseResponse<EventPlanPreview>> previewUpdate(@AuthenticationPrincipal UserDetailsImpl principal,
            @PathVariable UUID planId, @RequestBody EventPlanUpdateRequest request) {
        UUID actor = principal.getUser().getId();
        guard.checkPreview(actor);
        return response(service.previewUpdate(actor, planId, request));
    }
    @PostMapping
    public ResponseEntity<BaseResponse<EventPlanResponse>> create(@AuthenticationPrincipal UserDetailsImpl principal,
            @RequestBody EventPlanCreateRequest request) {
        UUID actor=principal.getUser().getId();guard.check(actor);return response(service.create(actor,request));
    }
    @GetMapping("/venue/{venueId}")
    public ResponseEntity<BaseResponse<PageResponse<EventPlanResponse>>> list(@AuthenticationPrincipal UserDetailsImpl principal,
            @PathVariable UUID venueId,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size) {
        return response(service.getOwnerPlans(principal.getUser().getId(),venueId,page,size));
    }
    @GetMapping("/{planId}")
    public ResponseEntity<BaseResponse<EventPlanResponse>> get(@AuthenticationPrincipal UserDetailsImpl principal,@PathVariable UUID planId) {
        return response(service.getOwner(principal.getUser().getId(),planId));
    }
    @PutMapping("/{planId}")
    public ResponseEntity<BaseResponse<EventPlanResponse>> update(@AuthenticationPrincipal UserDetailsImpl principal,
            @PathVariable UUID planId,@RequestBody EventPlanUpdateRequest request) {
        UUID actor=principal.getUser().getId();guard.check(actor);return response(service.update(actor,planId,request));
    }
    @PostMapping("/{planId}/stop")
    public ResponseEntity<BaseResponse<EventPlanResponse>> stop(@AuthenticationPrincipal UserDetailsImpl principal,
            @PathVariable UUID planId,@RequestBody EventPlanStopRequest request) {
        UUID actor=principal.getUser().getId();guard.check(actor);return response(service.stop(actor,planId,request));
    }
    @GetMapping("/{planId}/occurrences")
    public ResponseEntity<BaseResponse<PageResponse<EventPlanOccurrenceResponse>>> occurrences(@AuthenticationPrincipal UserDetailsImpl principal,
            @PathVariable UUID planId,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size) {
        return response(service.getOccurrences(principal.getUser().getId(),planId,page,size));
    }
    @PutMapping("/{planId}/occurrences/{scheduledDate}")
    public ResponseEntity<BaseResponse<EventPlanResponse>> override(@AuthenticationPrincipal UserDetailsImpl principal,@PathVariable UUID planId,
            @PathVariable @DateTimeFormat(iso=DateTimeFormat.ISO.DATE)LocalDate scheduledDate,@RequestBody EventPlanOverrideRequest request) {
        UUID actor=principal.getUser().getId();guard.check(actor);return response(service.override(actor,planId,scheduledDate,request));
    }
    @PostMapping("/{planId}/occurrences/{scheduledDate}/skip")
    public ResponseEntity<BaseResponse<EventPlanResponse>> skip(@AuthenticationPrincipal UserDetailsImpl principal,@PathVariable UUID planId,
            @PathVariable @DateTimeFormat(iso=DateTimeFormat.ISO.DATE)LocalDate scheduledDate,@RequestBody EventPlanVersionRequest request) {
        if(request==null||request.expectedVersion()==null)throw new SoundConnectException(ErrorType.INVALID_PARAMETER);
        UUID actor=principal.getUser().getId();guard.check(actor);return response(service.skip(actor,planId,scheduledDate,request.expectedVersion()));
    }
    static <T> ResponseEntity<BaseResponse<T>> response(T data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate())
                .body(BaseResponse.<T>builder().success(true).code(200).message("Etkinlik programı bilgileri getirildi.").data(data).build());
    }
}
