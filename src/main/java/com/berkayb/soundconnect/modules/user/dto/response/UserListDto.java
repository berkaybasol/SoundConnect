package com.berkayb.soundconnect.modules.user.dto.response;

import com.berkayb.soundconnect.modules.instrument.entity.Instrument;
import com.berkayb.soundconnect.modules.user.enums.City;
import com.berkayb.soundconnect.modules.user.enums.Gender;
import lombok.Builder;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Builder
public record UserListDto(
		UUID id,
		String username,
		Gender gender,
		City city,
		List<Instrument> instruments,
		Integer followers,
		Integer following,
		Set<String>roles

) {
}