package com.berkayb.soundconnect.modules.location.dto.response;

import java.util.List;

public record CityPrettyDto(
		String name,
		List<DistrictPrettyDto> districts
) {}