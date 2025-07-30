package com.berkayb.soundconnect.modules.user.dto.response;

import com.berkayb.soundconnect.modules.user.enums.City;
import com.berkayb.soundconnect.modules.user.enums.Gender;
import lombok.Builder;

import java.util.Set;
import java.util.UUID;

@Builder
public record UserListDto(
		UUID id,
		String username,
		Gender gender,
		City city,
		Integer followers,
		Integer following,
		Boolean emailVerified,
		Set<String>roles

) {
}