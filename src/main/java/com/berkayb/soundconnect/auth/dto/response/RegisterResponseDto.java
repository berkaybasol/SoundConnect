package com.berkayb.soundconnect.auth.dto.response;

import com.berkayb.soundconnect.modules.user.enums.UserStatus;

import java.util.UUID;

public record RegisterResponseDto(
		String email,
		UserStatus status,
		long otpTtlSeconds, // kalan sureyi flutter gostercek
		boolean mailQueued, // mail kuyruga sorunsuz atildi mi?
		UUID applicationId
		
) {
	public RegisterResponseDto(String email, UserStatus status, long otpTtlSeconds, boolean mailQueued) {
		this(email, status, otpTtlSeconds, mailQueued, null);
	}
}
