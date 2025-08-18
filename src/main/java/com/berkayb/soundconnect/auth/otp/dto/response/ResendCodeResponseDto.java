package com.berkayb.soundconnect.auth.otp.dto.response;

public record ResendCodeResponseDto(
		long otpTtlSeconds, // otp'den kalan sure
		boolean mailQueued,  // mail kuyruga atilabildi mi?
		long cooldownSeconds // tekrar gonderim icin bekleme suresi
) {
}