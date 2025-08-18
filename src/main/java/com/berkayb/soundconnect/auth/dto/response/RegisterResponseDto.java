package com.berkayb.soundconnect.auth.dto.response;

import com.berkayb.soundconnect.modules.user.enums.UserStatus;

public record RegisterResponseDto(
		String email,
		UserStatus status,
		long otpTtlSeconds, // kalan sureyi flutter gostercek
		boolean mailQueued // mail kuyruga sorunsuz atildi mi?
		
) {
}