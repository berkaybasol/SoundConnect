package com.berkayb.soundconnect.location.dto.response;

import java.util.List;

public record CityPrettyDto(
		String name,
		List<DistrictPrettyDto> districts
) {}