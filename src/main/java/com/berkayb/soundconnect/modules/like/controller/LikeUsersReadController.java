package com.berkayb.soundconnect.modules.like.controller;

import com.berkayb.soundconnect.modules.engagement.enums.EngagementTargetType;
import com.berkayb.soundconnect.modules.like.dto.LikeUsersPage;
import com.berkayb.soundconnect.modules.like.service.LikeUsersReadService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
import static com.berkayb.soundconnect.shared.constant.EndPoints.Like.BASE;

@RestController
@RequiredArgsConstructor
@RequestMapping(BASE)
@PreAuthorize("isAuthenticated()")
public class LikeUsersReadController {
    private final LikeUsersReadService service;

    @GetMapping("/{targetType}/{targetId}/users")
    public ResponseEntity<BaseResponse<LikeUsersPage>> get(
            @AuthenticationPrincipal(expression = "id") UUID viewerId,
            @PathVariable EngagementTargetType targetType,
            @PathVariable UUID targetId,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String cursor) {
        var page = service.get(viewerId, targetType, targetId, size, cursor);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate())
                .body(BaseResponse.<LikeUsersPage>builder().success(true).code(200)
                        .message("Beğenenler getirildi.").data(page).build());
    }
}
