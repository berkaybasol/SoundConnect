package com.berkayb.soundconnect.modules.tablegroup.profileshare;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.ServiceUnavailableRetryException;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import com.berkayb.soundconnect.shared.response.PageResponse;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;
import java.util.List;
import java.util.function.Supplier;

@RestController
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class TableGroupProfileShareController {
    private final TableGroupProfileShareService service;

    @ModelAttribute
    public void privateResponses(HttpServletResponse response) {
        response.setHeader("Cache-Control", "private, no-store");
    }

    @GetMapping("/api/v1/table-groups/{tableGroupId}/profile-share")
    public BaseResponse<TableGroupProfileShareResponse.State> get(@AuthenticationPrincipal UserDetailsImpl principal,
                                                                 @PathVariable UUID tableGroupId) {
        return response(() -> service.get(actor(principal), tableGroupId));
    }

    @PutMapping("/api/v1/table-groups/{tableGroupId}/profile-share")
    public BaseResponse<TableGroupProfileShareResponse.State> publish(@AuthenticationPrincipal UserDetailsImpl principal,
            @PathVariable UUID tableGroupId, @RequestBody TableGroupProfileShareUpdate command) {
        return response(() -> service.publish(actor(principal), tableGroupId, command));
    }

    @DeleteMapping("/api/v1/table-groups/profile-shares/{shareId}")
    public BaseResponse<Void> delete(@AuthenticationPrincipal UserDetailsImpl principal, @PathVariable UUID shareId) {
        return response(() -> { service.delete(actor(principal), shareId); return null; });
    }

    @GetMapping("/api/v1/public/listener-profiles/{profileId}/table-group-posts")
    public BaseResponse<PageResponse<TableGroupProfileShareResponse.Post>> list(@AuthenticationPrincipal UserDetailsImpl principal,
            @PathVariable UUID profileId, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return response(() -> service.list(actor(principal), profileId, page, size));
    }

    @GetMapping("/api/v1/public/listener-profiles/{profileId}/table-group-posts/lookup")
    public BaseResponse<List<TableGroupProfileShareResponse.Post>> lookup(@AuthenticationPrincipal UserDetailsImpl principal,
            @PathVariable UUID profileId, @RequestParam List<UUID> shareIds) {
        return response(() -> service.lookup(actor(principal), profileId, shareIds));
    }

    private UUID actor(UserDetailsImpl principal) {
        if (principal == null) throw new SoundConnectException(ErrorType.UNAUTHORIZED);
        return principal.getId();
    }

    private <T> BaseResponse<T> response(Supplier<T> work) {
        try {
            return BaseResponse.<T>builder().success(true).code(200).message("Masa profil paylaşımı.").data(work.get()).build();
        } catch (DataAccessException | TransactionException unavailable) {
            throw new ServiceUnavailableRetryException(ErrorType.TABLE_GROUP_PROFILE_SHARE_UNAVAILABLE, 5);
        }
    }
}
