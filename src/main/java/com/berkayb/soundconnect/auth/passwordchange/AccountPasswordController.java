package com.berkayb.soundconnect.auth.passwordchange;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.user.enums.AuthProvider;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users/me/password")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class AccountPasswordController {
    private final AccountPasswordChangeService passwords;

    public record PasswordMethod(AuthProvider provider) {}

    public record RenewedSession(String token) {
        @Override
        public String toString() {
            return "RenewedSession[redacted]";
        }
    }

    @GetMapping
    public ResponseEntity<BaseResponse<PasswordMethod>> method(@AuthenticationPrincipal UserDetailsImpl principal) {
        var method = new PasswordMethod(principal.getUser().getProvider());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(BaseResponse.<PasswordMethod>builder().success(true).code(200).data(method).build());
    }

    @PostMapping
    public ResponseEntity<BaseResponse<RenewedSession>> change(@AuthenticationPrincipal UserDetailsImpl principal,
                                                               @Valid @RequestBody PasswordChangeRequest request) {
        String token = passwords.change(principal.getId(), principal.getUser().getSessionVersion(), request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(BaseResponse.<RenewedSession>builder().success(true).code(200)
                        .data(new RenewedSession(token)).message("Şifren değiştirildi.").build());
    }
}
