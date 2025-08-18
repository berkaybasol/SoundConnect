package com.berkayb.soundconnect.auth.otp.dto.request;

public record VerifyCodeRequestDto(
		String email,
		String code
) {
}