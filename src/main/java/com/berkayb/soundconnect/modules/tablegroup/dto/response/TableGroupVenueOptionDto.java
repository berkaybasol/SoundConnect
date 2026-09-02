package com.berkayb.soundconnect.modules.tablegroup.dto.response;

import java.util.UUID;

/**
 * Minimal registered-venue option exposed only for TableGroup creation.
 */
public record TableGroupVenueOptionDto(
		UUID id,
		String name,
		String profilePictureUrl,
		String address,
		UUID cityId,
		String cityName,
		UUID districtId,
		String districtName,
		UUID neighborhoodId,
		String neighborhoodName
) {}
