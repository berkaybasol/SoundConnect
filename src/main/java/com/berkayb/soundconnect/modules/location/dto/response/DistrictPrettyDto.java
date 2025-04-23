package com.berkayb.soundconnect.modules.location.dto.response;

import java.util.List;

public record DistrictPrettyDto(
		String name,
		List<String> neighborhoods
) {}