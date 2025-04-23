package com.berkayb.soundconnect.modules.location.dto.response;

import java.util.UUID;

public record NeighborhoodResponseDto(
		UUID id,
		String name,
		UUID districtId
) {
}