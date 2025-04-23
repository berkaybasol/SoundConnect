package com.berkayb.soundconnect.modules.location.seed;

import java.util.List;

public record CitySeedDto(
		String name,
		List<DistrictSeedDto> districts
) {
}