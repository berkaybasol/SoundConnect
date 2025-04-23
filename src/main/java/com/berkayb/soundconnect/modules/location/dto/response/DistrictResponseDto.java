package com.berkayb.soundconnect.modules.location.dto.response;

import java.util.UUID;

public record DistrictResponseDto(
		UUID id,
		String name,
		UUID cityId
) {
}