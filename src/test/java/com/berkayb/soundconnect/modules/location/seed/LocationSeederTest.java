package com.berkayb.soundconnect.modules.location.seed;

import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.entity.District;
import com.berkayb.soundconnect.modules.location.entity.Neighborhood;
import com.berkayb.soundconnect.modules.location.repository.CityRepository;
import com.berkayb.soundconnect.modules.location.repository.DistrictRepository;
import com.berkayb.soundconnect.modules.location.repository.NeighborhoodRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LocationSeederTest {

	@Mock
	private CityRepository cityRepository;
	@Mock
	private DistrictRepository districtRepository;
	@Mock
	private NeighborhoodRepository neighborhoodRepository;

	@Test
	void seedsTheCompleteHierarchyInBatchesAndRemainsIdempotent() throws Exception {
		Map<String, City> cities = new HashMap<>();
		Map<DistrictKey, District> districts = new HashMap<>();
		Map<UUID, List<Neighborhood>> neighborhoodsByDistrictId = new HashMap<>();

		when(cityRepository.findByName(anyString()))
				.thenAnswer(invocation -> Optional.ofNullable(cities.get(invocation.getArgument(0, String.class))));
		when(cityRepository.save(any(City.class))).thenAnswer(invocation -> {
			City city = invocation.getArgument(0, City.class);
			city.setId(UUID.randomUUID());
			cities.put(city.getName(), city);
			return city;
		});

		when(districtRepository.findByNameAndCity_Id(anyString(), any(UUID.class))).thenAnswer(invocation -> {
			DistrictKey key = new DistrictKey(
					invocation.getArgument(1, UUID.class),
					invocation.getArgument(0, String.class)
			);
			return Optional.ofNullable(districts.get(key));
		});
		when(districtRepository.save(any(District.class))).thenAnswer(invocation -> {
			District district = invocation.getArgument(0, District.class);
			district.setId(UUID.randomUUID());
			districts.put(new DistrictKey(district.getCity().getId(), district.getName()), district);
			return district;
		});

		when(neighborhoodRepository.findAllByDistrict_Id(any(UUID.class)))
				.thenAnswer(invocation -> neighborhoodsByDistrictId.getOrDefault(
						invocation.getArgument(0, UUID.class),
						List.of()
				));
		when(neighborhoodRepository.saveAll(anyList())).thenAnswer(invocation -> {
			List<Neighborhood> batch = new ArrayList<>(invocation.getArgument(0));
			batch.forEach(neighborhood -> {
				neighborhood.setId(UUID.randomUUID());
				neighborhoodsByDistrictId
						.computeIfAbsent(neighborhood.getDistrict().getId(), ignored -> new ArrayList<>())
						.add(neighborhood);
			});
			return batch;
		});

		LocationSeeder seeder = new LocationSeeder(
				cityRepository,
				districtRepository,
				neighborhoodRepository,
				new ObjectMapper()
		);

		seeder.run(null);
		assertHierarchyCounts(cities, districts, neighborhoodsByDistrictId);

		seeder.run(null);
		assertHierarchyCounts(cities, districts, neighborhoodsByDistrictId);
		verify(cityRepository, times(81)).save(any(City.class));
		verify(districtRepository, times(973)).save(any(District.class));
		verify(neighborhoodRepository, times(973)).saveAll(anyList());
	}

	private void assertHierarchyCounts(
			Map<String, City> cities,
			Map<DistrictKey, District> districts,
			Map<UUID, List<Neighborhood>> neighborhoodsByDistrictId
	) {
		assertThat(cities).hasSize(81);
		assertThat(districts).hasSize(973);
		assertThat(neighborhoodsByDistrictId.values().stream().mapToInt(List::size).sum())
				.isEqualTo(32_254);
	}

	private record DistrictKey(UUID cityId, String name) {}
}
