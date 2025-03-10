package com.berkayb.soundconnect.dto.response;

import com.berkayb.soundconnect.entity.Instrument;
import com.berkayb.soundconnect.enums.City;
import com.berkayb.soundconnect.enums.Gender;
import com.berkayb.soundconnect.enums.Role;

import java.util.List;

public record UserListDto(
		String userName,
		Gender gender,
		City city,
		Role role,
		List<Instrument> instruments,
		Integer followers,
		Integer following
) {
}