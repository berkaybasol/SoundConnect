package com.berkayb.soundconnect.modules.feed.musician.api;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.feed.musician.abuse.MusicianFeedRateLimitGuard;
import com.berkayb.soundconnect.modules.feed.musician.core.MusicianFeedService;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.feed.musician", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequestMapping("/api/v1/feed/musician")
@PreAuthorize("hasRole('MUSICIAN')")
public class MusicianFeedController {
    private final MusicianFeedService service;
    private final MusicianFeedRateLimitGuard rateLimitGuard;

    @GetMapping
    public ResponseEntity<BaseResponse<MusicianFeedPageResponse>> get(
            @AuthenticationPrincipal UserDetailsImpl principal,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor,
            @RequestParam List<String> supportedItemTypes
    ) {
        if (principal == null) throw new SoundConnectException(ErrorType.UNAUTHORIZED);
        rateLimitGuard.checkPage(principal.getId(), cursor != null && !cursor.isBlank());
        var page = service.get(principal.getId(), limit, cursor, supportedItemTypes);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(BaseResponse.<MusicianFeedPageResponse>builder()
                        .success(true).message("Müzisyen akışı getirildi.").code(200).data(page).build());
    }
}
