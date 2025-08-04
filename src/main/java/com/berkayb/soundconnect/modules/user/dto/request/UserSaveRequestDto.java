package com.berkayb.soundconnect.modules.user.dto.request;

import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.user.enums.Gender;

import java.util.List;
import java.util.UUID;

public record UserSaveRequestDto(
		String username,
		String email,
		String phone,
		Gender gender,
		UUID roleId,
		City city,
		String password
) {
}