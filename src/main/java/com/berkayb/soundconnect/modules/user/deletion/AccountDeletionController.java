package com.berkayb.soundconnect.modules.user.deletion;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/users/me/account")
@RequiredArgsConstructor
public class AccountDeletionController {
    private final AccountDeletionCommandService deletion;

    @DeleteMapping
    @PreAuthorize("isAuthenticated()")
    public BaseResponse<Boolean> delete(@AuthenticationPrincipal UserDetailsImpl principal,
                                        @RequestBody @Valid AccountDeletionRequest request) {
        deletion.deleteSelf(principal.getId(), request);
        return BaseResponse.<Boolean>builder().success(true).code(200).data(true)
                .message("Hesabın kalıcı olarak silindi.").build();
    }
}
