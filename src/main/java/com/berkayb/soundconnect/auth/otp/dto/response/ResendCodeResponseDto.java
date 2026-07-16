package com.berkayb.soundconnect.auth.otp.dto.response;

public record ResendCodeResponseDto(
		long otpTtlSeconds, // otp'den kalan sure
		// Public resend endpoint never discloses actual delivery/account state.
		boolean mailQueued,
		long cooldownSeconds // tekrar gonderim icin bekleme suresi
) {
}
