package com.berkayb.soundconnect.modules.user.dto.request;

import com.berkayb.soundconnect.modules.user.enums.City;

import java.util.List;
import java.util.UUID;

public record UserUpdateRequestDto(
		UUID id, // lazim
		String userName,
		String password,
		String email,
		City city,
		List<UUID> instrumentIds
) {
}