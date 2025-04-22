package com.berkayb.soundconnect.location.seed;

import java.util.List;

public record DistrictSeedDto(
		String name,
		List<String> neighborhoods
) {
}