package com.berkayb.soundconnect.user.dto.response;

import com.berkayb.soundconnect.instrument.entity.Instrument;
import com.berkayb.soundconnect.role.entity.Role;
import com.berkayb.soundconnect.user.enums.City;
import com.berkayb.soundconnect.user.enums.Gender;
import lombok.Builder;

import java.util.List;

@Builder
public record UserListDto(
		String username,
		Gender gender,
		City city,
		Role role,
		List<Instrument> instruments,
		Integer followers,
		Integer following
) {
}