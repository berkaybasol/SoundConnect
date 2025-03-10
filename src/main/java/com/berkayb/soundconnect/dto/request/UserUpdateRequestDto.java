package com.berkayb.soundconnect.dto.request;

import com.berkayb.soundconnect.entity.Instrument;
import com.berkayb.soundconnect.enums.City;
import com.berkayb.soundconnect.enums.Role;

import java.util.List;

public record UserUpdateRequestDto(
		String userName,
		String password,
		String email,
		City city,
		List<Instrument> instruments
) {
}