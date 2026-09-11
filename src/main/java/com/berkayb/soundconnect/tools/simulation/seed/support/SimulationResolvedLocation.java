package com.berkayb.soundconnect.tools.simulation.seed.support;

import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.entity.District;
import com.berkayb.soundconnect.modules.location.entity.Neighborhood;

import java.util.Objects;

/** Catalog entities backing one canonical location from the simulation manifest. */
public record SimulationResolvedLocation(
		City city,
		District district,
		Neighborhood neighborhood,
		String addressLine
) {
	public SimulationResolvedLocation {
		Objects.requireNonNull(city, "city");
		Objects.requireNonNull(district, "district");
		Objects.requireNonNull(neighborhood, "neighborhood");
		addressLine = addressLine == null || addressLine.isBlank() ? null : addressLine.trim();
		if (!city.getId().equals(district.getCity().getId())) {
			throw new IllegalArgumentException("District does not belong to the resolved city");
		}
		if (!district.getId().equals(neighborhood.getDistrict().getId())) {
			throw new IllegalArgumentException("Neighborhood does not belong to the resolved district");
		}
	}

}
