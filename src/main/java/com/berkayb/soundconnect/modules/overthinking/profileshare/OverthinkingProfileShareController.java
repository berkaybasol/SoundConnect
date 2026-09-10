package com.berkayb.soundconnect.modules.overthinking.profileshare;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.shared.exception.*;
import com.berkayb.soundconnect.shared.response.*;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
import java.util.function.Supplier;

@RestController @RequiredArgsConstructor
public class OverthinkingProfileShareController {
    private final OverthinkingProfileShareService service;
    @ModelAttribute public void privateResponses(HttpServletResponse response) { response.setHeader("Cache-Control", "private, no-store"); }

    @GetMapping("/api/v1/overthinking/{postId}/profile-share") @PreAuthorize("isAuthenticated()")
    public BaseResponse<OverthinkingProfileShareResponse.State> get(@AuthenticationPrincipal UserDetailsImpl principal, @PathVariable UUID postId) {
        return response(() -> service.get(actor(principal), postId));
    }
    @PutMapping("/api/v1/overthinking/{postId}/profile-share") @PreAuthorize("isAuthenticated()")
    public BaseResponse<OverthinkingProfileShareResponse.State> publish(@AuthenticationPrincipal UserDetailsImpl principal,
            @PathVariable UUID postId, @RequestBody OverthinkingProfileShareUpdate command) {
        return response(() -> service.publish(actor(principal), postId, command));
    }
    @DeleteMapping("/api/v1/overthinking/profile-shares/{shareId}") @PreAuthorize("isAuthenticated()")
    public BaseResponse<Void> delete(@AuthenticationPrincipal UserDetailsImpl principal, @PathVariable UUID shareId) {
        return response(() -> { service.delete(actor(principal), shareId); return null; });
    }
    @GetMapping("/api/v1/public/listener-profiles/{profileId}/overthinking-posts") @PreAuthorize("isAuthenticated()")
    public BaseResponse<PageResponse<OverthinkingProfileShareResponse.Post>> list(@AuthenticationPrincipal UserDetailsImpl principal,
            @PathVariable UUID profileId, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return response(() -> service.list(actor(principal), profileId, page, size));
    }
    private UUID actor(UserDetailsImpl principal) {
        if (principal == null) throw new SoundConnectException(ErrorType.UNAUTHORIZED);
        return principal.getId();
    }
    private <T> BaseResponse<T> response(Supplier<T> work) {
        try { return BaseResponse.<T>builder().success(true).code(200).message("Profil paylaşımı güncellendi.").data(work.get()).build(); }
        catch (DataAccessException | TransactionException unavailable) {
            throw new ServiceUnavailableRetryException(ErrorType.OVERTHINKING_PROFILE_SHARE_UNAVAILABLE, 5);
        }
    }
}
