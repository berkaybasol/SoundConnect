package com.berkayb.soundconnect.modules.feed.musician.moderation;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
import static com.berkayb.soundconnect.modules.feed.musician.moderation.MusicianFeedOrphanRestrictionModels.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/musician-feed/restrictions")
@PreAuthorize("hasAuthority('MANAGE_MUSICIAN_FEED_REPORTS')")
public class MusicianFeedOrphanRestrictionController {
    private final MusicianFeedOrphanRestrictionService service;

    @GetMapping
    public ResponseEntity<BaseResponse<Page>> list(@AuthenticationPrincipal UserDetailsImpl principal,
            @RequestParam(required = false) Integer limit, @RequestParam(required = false) String cursor) {
        return ok(service.list(actor(principal), limit, cursor), "Kaydı silinmiş içerik kısıtlamaları getirildi.");
    }

    @PostMapping("/{reportId}/restore")
    public ResponseEntity<BaseResponse<Restored>> restore(@AuthenticationPrincipal UserDetailsImpl principal,
            @PathVariable UUID reportId, @Valid @RequestBody RestoreRequest request) {
        return ok(service.restore(actor(principal), reportId, request), "Kısıtlama kararı geri alındı.");
    }

    private UUID actor(UserDetailsImpl principal) {
        if (principal == null) throw new SoundConnectException(ErrorType.UNAUTHORIZED);
        return principal.getId();
    }
    private <T> ResponseEntity<BaseResponse<T>> ok(T data, String message) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate())
                .body(BaseResponse.<T>builder().success(true).code(200).message(message).data(data).build());
    }
}
