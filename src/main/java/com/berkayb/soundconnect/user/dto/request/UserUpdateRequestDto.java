package com.berkayb.soundconnect.user.dto.request;

import com.berkayb.soundconnect.user.enums.City;

import java.util.List;

public record UserUpdateRequestDto(
		Long id, // lazim
		String userName,
		String password,
		String email,
		City city,
		List<Long> instrumentIds
) {
}