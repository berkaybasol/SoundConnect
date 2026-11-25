package com.berkayb.soundconnect.auth.passwordchange;

import com.berkayb.soundconnect.shared.validation.BcryptPasswordLength;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PasswordChangeRequest(
        @NotBlank(message = "Mevcut şifreni gir.")
        @BcryptPasswordLength String currentPassword,
        @NotBlank(message = "Yeni şifreni gir.")
        @Size(min = 8, max = 72, message = "Yeni şifren en az 8, en fazla 72 karakter olmalı.")
        @BcryptPasswordLength String newPassword
) {
    @Override
    public String toString() {
        return "PasswordChangeRequest[redacted]";
    }
}
