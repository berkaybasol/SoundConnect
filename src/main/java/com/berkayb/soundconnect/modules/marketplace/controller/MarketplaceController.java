package com.berkayb.soundconnect.modules.marketplace.controller;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.marketplace.MarketplaceTypes.Status;
import com.berkayb.soundconnect.modules.marketplace.dto.MarketplaceRequests.*;
import com.berkayb.soundconnect.modules.marketplace.dto.MarketplaceResponses.*;
import com.berkayb.soundconnect.modules.marketplace.service.MarketplaceService;
import com.berkayb.soundconnect.shared.exception.*;
import com.berkayb.soundconnect.shared.response.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/user/marketplace")
@PreAuthorize("!hasRole('LISTENER') and hasAnyRole('MUSICIAN','STUDIO','VENUE')")
public class MarketplaceController {
    private final MarketplaceService service;

    @GetMapping("/categories")
    public ResponseEntity<BaseResponse<List<Category>>> categories(@AuthenticationPrincipal UserDetailsImpl principal) {
        return ok(service.categories(user(principal)));
    }
    @GetMapping("/listings")
    public ResponseEntity<BaseResponse<PageResponse<Listing>>> discovery(@AuthenticationPrincipal UserDetailsImpl principal,
            @Valid @ModelAttribute Filter filter,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return ok(service.discovery(user(principal),filter,page,size));
    }
    @GetMapping("/listings/{id}")
    public ResponseEntity<BaseResponse<Listing>> detail(@AuthenticationPrincipal UserDetailsImpl principal,@PathVariable UUID id) {
        return ok(service.detail(user(principal),id));
    }
    @PostMapping("/drafts")
    public ResponseEntity<BaseResponse<Listing>> draft(@AuthenticationPrincipal UserDetailsImpl principal,@Valid @RequestBody Draft body) {
        return response(service.createDraft(user(principal),body),HttpStatus.CREATED);
    }
    @PutMapping("/listings/{id}")
    public ResponseEntity<BaseResponse<Listing>> update(@AuthenticationPrincipal UserDetailsImpl principal,@PathVariable UUID id,@Valid @RequestBody Update body) {
        return ok(service.update(user(principal),id,body));
    }
    @PostMapping("/listings/{id}/publish")
    public ResponseEntity<BaseResponse<Listing>> publish(@AuthenticationPrincipal UserDetailsImpl principal,@PathVariable UUID id,@Valid @RequestBody Version body) {
        return ok(service.publish(user(principal),id,body));
    }
    @PostMapping("/listings/{id}/sold")
    public ResponseEntity<BaseResponse<Listing>> sold(@AuthenticationPrincipal UserDetailsImpl principal,@PathVariable UUID id,@Valid @RequestBody Version body) {
        return ok(service.sold(user(principal),id,body));
    }
    @PostMapping("/listings/{id}/withdraw")
    public ResponseEntity<BaseResponse<Listing>> withdraw(@AuthenticationPrincipal UserDetailsImpl principal,@PathVariable UUID id,@Valid @RequestBody Version body) {
        return ok(service.withdraw(user(principal),id,body));
    }
    @DeleteMapping("/listings/{id}")
    public ResponseEntity<BaseResponse<Void>> delete(@AuthenticationPrincipal UserDetailsImpl principal,@PathVariable UUID id,@RequestParam long expectedVersion) {
        service.deleteDraft(user(principal),id,expectedVersion);return ok(null);
    }
    @GetMapping("/my-listings")
    public ResponseEntity<BaseResponse<PageResponse<Listing>>> mine(@AuthenticationPrincipal UserDetailsImpl principal,@RequestParam(required=false) Status status,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return ok(service.mine(user(principal),status,page,size));
    }
    @GetMapping("/saved")
    public ResponseEntity<BaseResponse<PageResponse<Listing>>> saved(@AuthenticationPrincipal UserDetailsImpl principal,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return ok(service.saved(user(principal),page,size));
    }
    @PutMapping("/listings/{id}/saved")
    public ResponseEntity<BaseResponse<Boolean>> save(@AuthenticationPrincipal UserDetailsImpl principal,@PathVariable UUID id) {
        service.save(user(principal),id);return ok(true);
    }
    @DeleteMapping("/listings/{id}/saved")
    public ResponseEntity<BaseResponse<Boolean>> unsave(@AuthenticationPrincipal UserDetailsImpl principal,@PathVariable UUID id) {
        service.unsave(user(principal),id);return ok(false);
    }
    @PostMapping("/listings/{id}/reports")
    public ResponseEntity<BaseResponse<ReportReceipt>> report(@AuthenticationPrincipal UserDetailsImpl principal,@PathVariable UUID id,@Valid @RequestBody Report body) {
        return response(service.report(user(principal),id,body),HttpStatus.CREATED);
    }
    static UUID user(UserDetailsImpl principal) {
        if(principal==null) throw new SoundConnectException(ErrorType.UNAUTHORIZED);return principal.getId();
    }
    static <T> ResponseEntity<BaseResponse<T>> ok(T value){return response(value,HttpStatus.OK);}
    private static <T> ResponseEntity<BaseResponse<T>> response(T value,HttpStatus status) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore().cachePrivate())
                .body(BaseResponse.<T>builder().success(true).code(status.value()).message("İşlem tamamlandı.").data(value).build());
    }
}
