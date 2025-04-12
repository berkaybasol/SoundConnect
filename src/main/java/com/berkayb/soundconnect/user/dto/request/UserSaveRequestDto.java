package com.berkayb.soundconnect.user.dto.request;

import com.berkayb.soundconnect.role.entity.Role;
import com.berkayb.soundconnect.user.enums.City;
import com.berkayb.soundconnect.user.enums.Gender;

import java.util.List;

public record UserSaveRequestDto(
		String userName,
		String email,
		String phone,
		Gender gender,
		Role role,
		City city,
		List<Long> instrumentIds
) {
}