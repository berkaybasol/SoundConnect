package com.berkayb.soundconnect.modules.user.block;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.shared.constant.EndPoints;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping(EndPoints.User.BASE + "/me/blocks")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class UserBlockController {
    private final UserBlockService blocks;
    private final BlockedAccountQuery blockedAccounts;

    @GetMapping
    public BaseResponse<BlockedAccountQuery.BlockPage> list(@AuthenticationPrincipal UserDetailsImpl actor,
            @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size) {
        return ok(blockedAccounts.list(actor.getId(), page, size));
    }

    @GetMapping("/{target}")
    public BaseResponse<BlockStatus> status(@AuthenticationPrincipal UserDetailsImpl actor, @PathVariable UUID target) {
        return ok(blocks.status(actor.getId(), target));
    }

    @PutMapping("/{target}")
    public BaseResponse<BlockStatus> block(@AuthenticationPrincipal UserDetailsImpl actor, @PathVariable UUID target) {
        return ok(blocks.block(actor.getId(), target));
    }

    @DeleteMapping("/{target}")
    public BaseResponse<BlockStatus> unblock(@AuthenticationPrincipal UserDetailsImpl actor, @PathVariable UUID target) {
        return ok(blocks.unblock(actor.getId(), target));
    }

    private static <T> BaseResponse<T> ok(T value) {
        return BaseResponse.<T>builder().success(true).code(200).data(value).build();
    }
}
