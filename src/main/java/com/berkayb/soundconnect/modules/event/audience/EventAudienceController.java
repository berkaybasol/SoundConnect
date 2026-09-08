package com.berkayb.soundconnect.modules.event.audience;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.shared.exception.*;
import com.berkayb.soundconnect.shared.response.*;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
import java.util.function.Supplier;

@RestController @RequiredArgsConstructor
public class EventAudienceController {
    private final EventAudienceService service;
    private final EventAudienceRateGuard guard;
    @ModelAttribute public void privateResponses(HttpServletResponse response) { response.setHeader("Cache-Control", "private, no-store"); }

    @GetMapping("/api/v1/user/event-intents/{eventId}") @PreAuthorize("hasAnyRole('LISTENER','MUSICIAN')")
    public BaseResponse<EventIntentResponse.State> get(@AuthenticationPrincipal UserDetailsImpl principal, @PathVariable UUID eventId) {
        return response(() -> service.get(actor(principal), eventId));
    }
    @PutMapping("/api/v1/user/event-intents/{eventId}") @PreAuthorize("hasAnyRole('LISTENER','MUSICIAN')")
    public BaseResponse<EventIntentResponse.State> update(@AuthenticationPrincipal UserDetailsImpl principal, @PathVariable UUID eventId,
                                                         @RequestBody EventIntentUpdate update) {
        UUID actor = actor(principal);
        return response(() -> { service.requireAuthority(actor); guard.check(actor); return service.update(actor, eventId, update); });
    }
    @GetMapping("/api/v1/user/event-intents") @PreAuthorize("hasAnyRole('LISTENER','MUSICIAN')")
    public BaseResponse<PageResponse<EventIntentResponse.State>> mine(@AuthenticationPrincipal UserDetailsImpl principal,
            @RequestParam(defaultValue = "UPCOMING") EventIntentPeriod period, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return response(() -> service.mine(actor(principal), period, page, size));
    }
    @GetMapping("/api/v1/public/listener-profiles/{profileId}/event-posts") @PreAuthorize("isAuthenticated()")
    public BaseResponse<PageResponse<EventIntentResponse.Post>> posts(@AuthenticationPrincipal UserDetailsImpl principal,
            @PathVariable UUID profileId, @RequestParam(defaultValue = "ALL") EventIntentPeriod period,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return response(() -> service.posts(actor(principal), profileId, period, page, size));
    }
    private UUID actor(UserDetailsImpl principal) {
        if (principal == null) throw new SoundConnectException(ErrorType.UNAUTHORIZED);
        return principal.getId();
    }
    private <T> BaseResponse<T> response(Supplier<T> work) {
        try { return BaseResponse.<T>builder().success(true).code(200).message("Etkinlik planı güncellendi.").data(work.get()).build(); }
        catch (DataAccessException | TransactionException unavailable) { throw new ServiceUnavailableRetryException(ErrorType.EVENT_INTENT_UNAVAILABLE, 5); }
    }
}
