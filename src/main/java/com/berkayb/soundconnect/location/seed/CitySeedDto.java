package com.berkayb.soundconnect.location.seed;

import java.util.List;

public record CitySeedDto(
		String name,
		List<DistrictSeedDto> districts
) {
}