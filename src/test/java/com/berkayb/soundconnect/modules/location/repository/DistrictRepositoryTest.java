package com.berkayb.soundconnect.modules.location.repository;

import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.entity.District;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class DistrictRepositoryTest {
	
	@Autowired
	private CityRepository cityRepository;
	
	@Autowired
	private DistrictRepository districtRepository;
	
	@Test
	void existsByNameAndCityId_and_findDistrictsByCityId_work() {
		// given: bir city ve iki district oluşturalım
		City ankara = cityRepository.saveAndFlush(City.builder().name("Ankara").build());
		City istanbul = cityRepository.saveAndFlush(City.builder().name("İstanbul").build());
		
		District cankaya = districtRepository.saveAndFlush(
				District.builder().name("Çankaya").city(ankara).build()
		);
		District kecioren = districtRepository.saveAndFlush(
				District.builder().name("Keçiören").city(ankara).build()
		);
		District kadikoy = districtRepository.saveAndFlush(
				District.builder().name("Kadıköy").city(istanbul).build()
		);
		
		UUID ankaraId = ankara.getId();
		UUID istanbulId = istanbul.getId();
		
		// when-then: existsByNameAndCity_Id
		assertThat(districtRepository.existsByNameAndCity_Id("Çankaya", ankaraId)).isTrue();
		assertThat(districtRepository.existsByNameAndCity_Id("Çankaya", istanbulId)).isFalse();
		assertThat(districtRepository.existsByNameAndCity_Id("Uydurma", ankaraId)).isFalse();
		
		// when: findDistrictsByCity_Id (Ankara için)
		List<District> ankaraDistricts = districtRepository.findDistrictsByCity_Id(ankaraId);
		
		// then
		assertThat(ankaraDistricts)
				.extracting(District::getName)
				.containsExactlyInAnyOrder("Çankaya", "Keçiören")
				.doesNotContain("Kadıköy");
	}
}