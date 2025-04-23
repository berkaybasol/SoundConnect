package com.berkayb.soundconnect.modules.location.seed;

import java.util.List;

public record DistrictSeedDto(
		String name,
		List<String> neighborhoods
) {
}