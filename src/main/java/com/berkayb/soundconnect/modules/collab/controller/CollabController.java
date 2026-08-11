package com.berkayb.soundconnect.modules.collab.controller;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.collab.dto.request.*;
import com.berkayb.soundconnect.modules.collab.dto.response.*;
import com.berkayb.soundconnect.modules.collab.enums.*;
import com.berkayb.soundconnect.modules.collab.service.*;
import com.berkayb.soundconnect.shared.exception.*;
import com.berkayb.soundconnect.shared.response.*;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.*;

import static com.berkayb.soundconnect.shared.constant.EndPoints.Collab.*;

@RestController
@RequiredArgsConstructor
@RequestMapping(BASE)
@PreAuthorize("hasAnyRole('MUSICIAN','VENUE','STUDIO')")
public class CollabController {
    private final CollabService service;

    @GetMapping(ACTORS_ME)
    @Operation(summary = "List the authenticated user's Collab publisher/applicant profiles")
    public ResponseEntity<BaseResponse<List<CollabActorSummary>>> actorsMine(
            @AuthenticationPrincipal UserDetailsImpl principal) {
        return ok(service.actorsMine(userId(principal)), "Collab profilleri listelendi.");
    }

    @GetMapping
    @Operation(summary = "Discover open Collab listings")
    public ResponseEntity<BaseResponse<PageResponse<CollabListingResponse>>> discovery(
            @AuthenticationPrincipal UserDetailsImpl principal,
            @Valid @ParameterObject @ModelAttribute CollabFilterRequest filter,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ok(service.discovery(userId(principal), filter, page, size), "İlanlar listelendi.");
    }

    @GetMapping(BY_ID)
    public ResponseEntity<BaseResponse<CollabListingResponse>> detail(
            @AuthenticationPrincipal UserDetailsImpl principal, @PathVariable UUID listingId) {
        return ok(service.detail(userId(principal), listingId), "İlan getirildi.");
    }

    @PostMapping(DRAFTS)
    public ResponseEntity<BaseResponse<CollabListingResponse>> createDraft(
            @AuthenticationPrincipal UserDetailsImpl principal,
            @Valid @RequestBody CollabDraftCreateRequest request) {
        return created(service.createDraft(userId(principal), request), "Taslak oluşturuldu.");
    }

    @PutMapping(BY_ID)
    public ResponseEntity<BaseResponse<CollabListingResponse>> update(
            @AuthenticationPrincipal UserDetailsImpl principal, @PathVariable UUID listingId,
            @Valid @RequestBody CollabUpdateRequest request) {
        return ok(service.update(userId(principal), listingId, request), "İlan güncellendi.");
    }

    @PostMapping(PUBLISH)
    public ResponseEntity<BaseResponse<CollabListingResponse>> publish(
            @AuthenticationPrincipal UserDetailsImpl principal, @PathVariable UUID listingId,
            @Valid @RequestBody ExpectedVersionRequest request) {
        return ok(service.publish(userId(principal), listingId, request), "İlan yayınlandı.");
    }

    @DeleteMapping(DRAFT_BY_ID)
    public ResponseEntity<BaseResponse<Void>> deleteDraft(
            @AuthenticationPrincipal UserDetailsImpl principal, @PathVariable UUID listingId,
            @Valid @RequestBody ExpectedVersionRequest request) {
        service.deleteDraft(userId(principal), listingId, request);
        return ok(null, "Taslak silindi.");
    }

    @PostMapping(CLOSE)
    public ResponseEntity<BaseResponse<CollabListingResponse>> close(
            @AuthenticationPrincipal UserDetailsImpl principal, @PathVariable UUID listingId,
            @Valid @RequestBody ExpectedVersionRequest request) {
        return ok(service.close(userId(principal), listingId, request), "İlan kapatıldı.");
    }

    @GetMapping(ME_LISTINGS)
    public ResponseEntity<BaseResponse<PageResponse<CollabListingResponse>>> listingsMine(
            @AuthenticationPrincipal UserDetailsImpl principal,
            @RequestParam(required = false) CollabListingStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ok(service.listingsMine(userId(principal), status, page, size), "İlanlarım listelendi.");
    }

    @PutMapping(SAVED)
    public ResponseEntity<BaseResponse<CollabListingResponse>> save(
            @AuthenticationPrincipal UserDetailsImpl principal, @PathVariable UUID listingId) {
        return ok(service.save(userId(principal), listingId), "İlan kaydedildi.");
    }

    @DeleteMapping(SAVED)
    public ResponseEntity<BaseResponse<Void>> unsave(
            @AuthenticationPrincipal UserDetailsImpl principal, @PathVariable UUID listingId) {
        service.unsave(userId(principal), listingId);
        return ok(null, "İlan kayıtlardan çıkarıldı.");
    }

    @GetMapping(ME_SAVED)
    public ResponseEntity<BaseResponse<PageResponse<CollabListingResponse>>> savedMine(
            @AuthenticationPrincipal UserDetailsImpl principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ok(service.savedMine(userId(principal), page, size), "Kaydedilen ilanlar listelendi.");
    }

    @PostMapping(APPLICATIONS)
    public ResponseEntity<BaseResponse<CollabApplicationResponse>> apply(
            @AuthenticationPrincipal UserDetailsImpl principal, @PathVariable UUID listingId,
            @Valid @RequestBody CollabApplicationCreateRequest request) {
        return created(service.apply(userId(principal), listingId, request), "Başvuru gönderildi.");
    }

    @GetMapping(INCOMING)
    public ResponseEntity<BaseResponse<PageResponse<CollabApplicationResponse>>> incoming(
            @AuthenticationPrincipal UserDetailsImpl principal, @PathVariable UUID listingId,
            @RequestParam(required = false) CollabApplicationStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ok(service.incoming(userId(principal), listingId, status, page, size), "Başvurular listelendi.");
    }

    @GetMapping(ME_APPLICATIONS)
    public ResponseEntity<BaseResponse<PageResponse<CollabApplicationResponse>>> applicationsMine(
            @AuthenticationPrincipal UserDetailsImpl principal,
            @RequestParam(required = false) CollabApplicationStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ok(service.applicationsMine(userId(principal), status, page, size), "Başvurularım listelendi.");
    }

    @PostMapping(ACCEPT)
    public ResponseEntity<BaseResponse<CollabJobResponse>> accept(
            @AuthenticationPrincipal UserDetailsImpl principal, @PathVariable UUID applicationId,
            @Valid @RequestBody ExpectedVersionRequest request) {
        return ok(service.accept(userId(principal), applicationId, request), "Başvuru kabul edildi.");
    }

    @PostMapping(REJECT)
    public ResponseEntity<BaseResponse<CollabApplicationResponse>> reject(
            @AuthenticationPrincipal UserDetailsImpl principal, @PathVariable UUID applicationId,
            @Valid @RequestBody ExpectedVersionRequest request) {
        return ok(service.reject(userId(principal), applicationId, request), "Başvuru reddedildi.");
    }

    @PostMapping(WITHDRAW)
    public ResponseEntity<BaseResponse<CollabApplicationResponse>> withdraw(
            @AuthenticationPrincipal UserDetailsImpl principal, @PathVariable UUID applicationId,
            @Valid @RequestBody ExpectedVersionRequest request) {
        return ok(service.withdraw(userId(principal), applicationId, request), "Başvuru geri çekildi.");
    }

    @GetMapping(ME_JOBS)
    public ResponseEntity<BaseResponse<PageResponse<CollabJobResponse>>> jobsMine(
            @AuthenticationPrincipal UserDetailsImpl principal,
            @RequestParam(required = false) CollabJobStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ok(service.jobsMine(userId(principal), status, page, size), "Isler listelendi.");
    }

    @PostMapping(CONFIRM_COMPLETION)
    public ResponseEntity<BaseResponse<CollabJobResponse>> confirmCompletion(
            @AuthenticationPrincipal UserDetailsImpl principal, @PathVariable UUID jobId,
            @Valid @RequestBody ExpectedVersionRequest request) {
        return ok(service.confirmCompletion(userId(principal), jobId, request), "Tamamlama onayı kaydedildi.");
    }

    @PostMapping(JOB_REVIEWS)
    public ResponseEntity<BaseResponse<CollabReviewResponse>> review(
            @AuthenticationPrincipal UserDetailsImpl principal, @PathVariable UUID jobId,
            @Valid @RequestBody CollabReviewCreateRequest request) {
        return created(service.review(userId(principal), jobId, request), "Değerlendirme kaydedildi.");
    }

    @GetMapping(ACTOR_REVIEWS)
    public ResponseEntity<BaseResponse<PageResponse<CollabReviewResponse>>> actorReviews(
            @AuthenticationPrincipal UserDetailsImpl principal, @PathVariable UUID actorId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        userId(principal);
        return ok(service.actorReviews(actorId, page, size), "Değerlendirmeler listelendi.");
    }

    @PostMapping(REPORTS)
    public ResponseEntity<BaseResponse<CollabReportResponse>> report(
            @AuthenticationPrincipal UserDetailsImpl principal, @PathVariable UUID listingId,
            @Valid @RequestBody CollabReportCreateRequest request) {
        return created(service.report(userId(principal), listingId, request), "Rapor alındı.");
    }

    private UUID userId(UserDetailsImpl principal) {
        if (principal == null) throw new SoundConnectException(ErrorType.UNAUTHORIZED);
        return principal.getId();
    }

    private <T> ResponseEntity<BaseResponse<T>> ok(T data, String message) {
        return ResponseEntity.ok(BaseResponse.<T>builder().success(true).message(message).code(200).data(data).build());
    }

    private <T> ResponseEntity<BaseResponse<T>> created(T data, String message) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(BaseResponse.<T>builder().success(true).message(message).code(201).data(data).build());
    }
}
