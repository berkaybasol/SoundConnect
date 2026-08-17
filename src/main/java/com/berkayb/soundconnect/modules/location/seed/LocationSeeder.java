package com.berkayb.soundconnect.modules.location.seed;

import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.entity.District;
import com.berkayb.soundconnect.modules.location.entity.Neighborhood;
import com.berkayb.soundconnect.modules.location.repository.CityRepository;
import com.berkayb.soundconnect.modules.location.repository.DistrictRepository;
import com.berkayb.soundconnect.modules.location.repository.NeighborhoodRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(value = "app.location.seed.enabled", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
public class LocationSeeder implements ApplicationRunner {

	private final CityRepository cityRepository;
	private final DistrictRepository districtRepository;
	private final NeighborhoodRepository neighborhoodRepository;
	private final ObjectMapper objectMapper;

	@Override
	@Transactional
	public void run(ApplicationArguments args) throws Exception {
		log.info("[location-seed] synchronizing location reference data");

		List<CitySeedDto> cities;
		try (InputStream inputStream = new ClassPathResource("location-seed.json").getInputStream()) {
			cities = objectMapper.readValue(inputStream, new TypeReference<>() {});
		}

		int createdCities = 0;
		int createdDistricts = 0;
		int createdNeighborhoods = 0;
		for (CitySeedDto citySeed : cities) {
			City city = cityRepository.findByName(citySeed.name()).orElse(null);
			if (city == null) {
				city = cityRepository.save(City.builder().name(citySeed.name()).build());
				createdCities++;
			}

			for (DistrictSeedDto districtSeed : citySeed.districts()) {
				District district = districtRepository.findByNameAndCity_Id(districtSeed.name(), city.getId())
						.orElse(null);
				if (district == null) {
					district = districtRepository.save(District.builder()
							.name(districtSeed.name())
							.city(city)
							.build());
					createdDistricts++;
				}

				Set<String> existingNeighborhoodNames = neighborhoodRepository.findAllByDistrict_Id(district.getId())
						.stream()
						.map(Neighborhood::getName)
						.collect(java.util.stream.Collectors.toCollection(HashSet::new));
				List<Neighborhood> missingNeighborhoods = new ArrayList<>();
				for (String neighborhoodName : districtSeed.neighborhoods()) {
					if (existingNeighborhoodNames.add(neighborhoodName)) {
						missingNeighborhoods.add(Neighborhood.builder()
								.name(neighborhoodName)
								.district(district)
								.build());
					}
				}
				if (!missingNeighborhoods.isEmpty()) {
					neighborhoodRepository.saveAll(missingNeighborhoods);
					createdNeighborhoods += missingNeighborhoods.size();
				}
			}
		}

		log.info("[location-seed] synchronized citiesCreated={} districtsCreated={} neighborhoodsCreated={}",
				createdCities, createdDistricts, createdNeighborhoods);
	}
}
