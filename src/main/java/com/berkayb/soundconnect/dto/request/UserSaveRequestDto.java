package com.berkayb.soundconnect.dto.request;

import com.berkayb.soundconnect.enums.City;
import com.berkayb.soundconnect.enums.Gender;
import com.berkayb.soundconnect.enums.Role;

public record UserSaveRequestDto(
		String userName,
		String email,
		String phone,
		Gender gender,
		Role role,
		City city
) {
}