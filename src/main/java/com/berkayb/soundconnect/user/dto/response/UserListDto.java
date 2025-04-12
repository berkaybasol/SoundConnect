package com.berkayb.soundconnect.user.dto.response;

import com.berkayb.soundconnect.instrument.entity.Instrument;
import com.berkayb.soundconnect.user.enums.City;
import com.berkayb.soundconnect.user.enums.Gender;
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