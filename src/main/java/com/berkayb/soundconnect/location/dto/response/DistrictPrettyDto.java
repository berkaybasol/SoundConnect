package com.berkayb.soundconnect.location.dto.response;

import java.util.List;

public record DistrictPrettyDto(
		String name,
		List<String> neighborhoods
) {}