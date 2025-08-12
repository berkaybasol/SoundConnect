package com.berkayb.soundconnect.modules.location.repository;

import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.entity.District;
import com.berkayb.soundconnect.modules.location.entity.Neighborhood;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Tag("repo")
class NeighborhoodRepositoryTest {
	
	@Autowired
	private CityRepository cityRepository;
	
	@Autowired
	private DistrictRepository districtRepository;
	
	@Autowired
	private NeighborhoodRepository neighborhoodRepository;
	
	@Test
	void existsByNameAndDistrictId_and_findAllByDistrictId_work() {
		// given: City → District → Neighborhood hiyerarşisi
		City ankara = cityRepository.saveAndFlush(City.builder().name("Ankara").build());
		
		District cankaya = districtRepository.saveAndFlush(
				District.builder().name("Çankaya").city(ankara).build()
		);
		District kecioren = districtRepository.saveAndFlush(
				District.builder().name("Keçiören").city(ankara).build()
		);
		
		Neighborhood bahcelievler = neighborhoodRepository.saveAndFlush(
				Neighborhood.builder().name("Bahçelievler").district(cankaya).build()
		);
		Neighborhood yuzuncuyil = neighborhoodRepository.saveAndFlush(
				Neighborhood.builder().name("Yüzüncüyıl").district(cankaya).build()
		);
		Neighborhood etlik = neighborhoodRepository.saveAndFlush(
				Neighborhood.builder().name("Etlik").district(kecioren).build()
		);
		
		UUID cankayaId = cankaya.getId();
		UUID keciorenId = kecioren.getId();
		
		// when-then: existsByNameAndDistrict_Id
		assertThat(neighborhoodRepository.existsByNameAndDistrict_Id("Bahçelievler", cankayaId)).isTrue();
		assertThat(neighborhoodRepository.existsByNameAndDistrict_Id("Bahçelievler", keciorenId)).isFalse();
		assertThat(neighborhoodRepository.existsByNameAndDistrict_Id("Uydurma", cankayaId)).isFalse();
		
		// when: findAllByDistrict_Id (Çankaya için)
		List<Neighborhood> cnkList = neighborhoodRepository.findAllByDistrict_Id(cankayaId);
		
		// then
		assertThat(cnkList)
				.extracting(Neighborhood::getName)
				.containsExactlyInAnyOrder("Bahçelievler", "Yüzüncüyıl")
				.doesNotContain("Etlik");
	}
}