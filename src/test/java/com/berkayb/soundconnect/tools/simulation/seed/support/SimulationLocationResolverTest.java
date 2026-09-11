package com.berkayb.soundconnect.tools.simulation.seed.support;

import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.entity.District;
import com.berkayb.soundconnect.modules.location.entity.Neighborhood;
import com.berkayb.soundconnect.modules.location.repository.CityRepository;
import com.berkayb.soundconnect.modules.location.repository.DistrictRepository;
import com.berkayb.soundconnect.modules.location.repository.NeighborhoodRepository;
import com.berkayb.soundconnect.tools.simulation.SimulationRuntimeGuard;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SimulationLocationResolverTest {

	private final SimulationRuntimeGuard guard = mock(SimulationRuntimeGuard.class);
	private final CityRepository cities = mock(CityRepository.class);
	private final DistrictRepository districts = mock(DistrictRepository.class);
	private final NeighborhoodRepository neighborhoods = mock(NeighborhoodRepository.class);
	private final SimulationLocationResolver resolver = new SimulationLocationResolver(
			guard, cities, districts, neighborhoods);

	@Test
	void resolvesEachLevelInsideItsParentScope() {
		City city = City.builder().id(UUID.randomUUID()).name("İstanbul").build();
		District district = District.builder().id(UUID.randomUUID()).name("Kadıköy").city(city).build();
		Neighborhood other = Neighborhood.builder().id(UUID.randomUUID()).name("Caferağa").district(district).build();
		Neighborhood requested = Neighborhood.builder().id(UUID.randomUUID()).name("Rasimpaşa").district(district).build();
		when(cities.findByName("İstanbul")).thenReturn(Optional.of(city));
		when(districts.findByNameAndCity_Id("Kadıköy", city.getId())).thenReturn(Optional.of(district));
		when(neighborhoods.findAllByDistrict_Id(district.getId())).thenReturn(List.of(other, requested));

		SimulationResolvedLocation result = resolver.resolve(new SimulationWorldManifest.Location(
				"İstanbul", "Kadıköy", "Rasimpaşa", "Moda Caddesi 12"));

		assertThat(result.city()).isSameAs(city);
		assertThat(result.district()).isSameAs(district);
		assertThat(result.neighborhood()).isSameAs(requested);
		assertThat(result.addressLine()).isEqualTo("Moda Caddesi 12");
		verify(guard).assertRuntimeAllowed();
	}

	@Test
	void failsBeforeReturningAnUnseededManifestLocation() {
		when(cities.findByName("Atlantis")).thenReturn(Optional.empty());

		assertThatThrownBy(() -> resolver.resolve(new SimulationWorldManifest.Location(
				"Atlantis", "Merkez", "Sahil", "1. Cadde")))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("absent from the seeded location catalog")
				.hasMessageContaining("Atlantis");
		verify(guard).assertRuntimeAllowed();
	}

	@Test
	void permitsPersonalCatalogLocationsWithoutAStreetAddress() {
		City city = City.builder().id(UUID.randomUUID()).name("Ankara").build();
		District district = District.builder().id(UUID.randomUUID()).name("Çankaya").city(city).build();
		Neighborhood neighborhood = Neighborhood.builder().id(UUID.randomUUID()).name("Kızılay").district(district).build();
		when(cities.findByName("Ankara")).thenReturn(Optional.of(city));
		when(districts.findByNameAndCity_Id("Çankaya", city.getId())).thenReturn(Optional.of(district));
		when(neighborhoods.findAllByDistrict_Id(district.getId())).thenReturn(List.of(neighborhood));

		SimulationResolvedLocation result = resolver.resolve(new SimulationWorldManifest.Location(
				"Ankara", "Çankaya", "Kızılay", null));

		assertThat(result.addressLine()).isNull();
	}
}
