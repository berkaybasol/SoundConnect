package com.berkayb.soundconnect.tools.simulation.seed.support;

import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.entity.District;
import com.berkayb.soundconnect.modules.location.entity.Neighborhood;
import com.berkayb.soundconnect.modules.location.repository.CityRepository;
import com.berkayb.soundconnect.modules.location.repository.DistrictRepository;
import com.berkayb.soundconnect.modules.location.repository.NeighborhoodRepository;
import com.berkayb.soundconnect.tools.simulation.SimulationRuntimeGuard;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/** Resolves stable manifest names after the normal location catalog seeder has run. */
@Component
@Profile("local & simulation")
@ConditionalOnProperty(prefix = "app.simulation", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class SimulationLocationResolver {

	private final SimulationRuntimeGuard runtimeGuard;
	private final CityRepository cityRepository;
	private final DistrictRepository districtRepository;
	private final NeighborhoodRepository neighborhoodRepository;

	@Transactional(readOnly = true)
	public SimulationResolvedLocation resolve(SimulationWorldManifest.Location requested) {
		runtimeGuard.assertRuntimeAllowed();
		Objects.requireNonNull(requested, "requested location");
		String cityName = requireText(requested.city(), "city");
		String districtName = requireText(requested.district(), "district");
		String neighborhoodName = requireText(requested.neighborhood(), "neighborhood");

		City city = cityRepository.findByName(cityName)
				.orElseThrow(() -> missing("city", cityName));
		District district = districtRepository.findByNameAndCity_Id(districtName, city.getId())
				.orElseThrow(() -> missing("district", cityName + "/" + districtName));
		Neighborhood neighborhood = neighborhoodRepository.findAllByDistrict_Id(district.getId()).stream()
				.filter(candidate -> neighborhoodName.equals(candidate.getName()))
				.findFirst()
				.orElseThrow(() -> missing(
						"neighborhood", cityName + "/" + districtName + "/" + neighborhoodName));

		return new SimulationResolvedLocation(city, district, neighborhood, requested.addressLine());
	}

	private static IllegalStateException missing(String level, String value) {
		return new IllegalStateException("Simulation manifest " + level
				+ " is absent from the seeded location catalog: " + value);
	}

	private static String requireText(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("Simulation location " + field + " is required");
		}
		return value.trim();
	}
}
