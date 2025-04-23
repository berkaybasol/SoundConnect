package com.berkayb.soundconnect.modules.location.seed;

import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.entity.District;
import com.berkayb.soundconnect.modules.location.entity.Neighborhood;
import com.berkayb.soundconnect.modules.location.repository.CityRepository;
import com.berkayb.soundconnect.modules.location.repository.DistrictRepository;
import com.berkayb.soundconnect.modules.location.repository.NeighborhoodRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class LocationSeeder {
	
	private final CityRepository cityRepository;
	private final DistrictRepository districtRepository;
	private final NeighborhoodRepository neighborhoodRepository;
	
	@PostConstruct
	public void seed() {
		if (cityRepository.count() > 0) {
			log.info("Seed already executed, skipping...");
			return;
		}
		
		try {
			log.info("Starting location seed...");
			
			// JSON dosyasını oku
			InputStream inputStream = new ClassPathResource("location-seed.json").getInputStream();
			ObjectMapper mapper = new ObjectMapper();
			List<CitySeedDto> cities = mapper.readValue(inputStream, new TypeReference<>() {});
			
			for (CitySeedDto citySeed : cities) {
				City city = City.builder().name(citySeed.name()).build();
				City savedCity = cityRepository.save(city);
				
				for (DistrictSeedDto districtSeed : citySeed.districts()) {
					District district = District.builder()
					                            .name(districtSeed.name())
					                            .city(savedCity)
					                            .build();
					District savedDistrict = districtRepository.save(district);
					
					for (String neighborhoodName : districtSeed.neighborhoods()) {
						Neighborhood neighborhood = Neighborhood.builder()
						                                        .name(neighborhoodName)
						                                        .district(savedDistrict)
						                                        .build();
						neighborhoodRepository.save(neighborhood);
					}
				}
			}
			
			log.info(" location seed completed!");
			
		} catch (Exception e) {
			log.error(" failed to seed locations", e);
		}
	}
}