package com.berkayb.soundconnect.modules.location.repository;

import com.berkayb.soundconnect.modules.location.entity.City;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Tag("repo")
class CityRepositoryTest {
	
	@Autowired
	private CityRepository cityRepository;
	
	@Test
	void existsByName_and_findByName_work() {
		// given
		City c = City.builder().name("Ankara").build();
		cityRepository.saveAndFlush(c);
		
		// when & then
		assertThat(cityRepository.existsByName("Ankara")).isTrue();
		assertThat(cityRepository.findByName("Ankara"))
				.isPresent()
				.get()
				.extracting(City::getName).isEqualTo("Ankara");
	}
}