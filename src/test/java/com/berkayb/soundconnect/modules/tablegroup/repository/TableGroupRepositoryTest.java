package com.berkayb.soundconnect.modules.tablegroup.repository;

import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.entity.District;
import com.berkayb.soundconnect.modules.location.entity.Neighborhood;
import com.berkayb.soundconnect.modules.location.repository.CityRepository;
import com.berkayb.soundconnect.modules.location.repository.DistrictRepository;
import com.berkayb.soundconnect.modules.location.repository.NeighborhoodRepository;
import com.berkayb.soundconnect.modules.tablegroup.entity.TableGroup;
import com.berkayb.soundconnect.modules.tablegroup.enums.TableGroupStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(SpringExtension.class)
@DataJpaTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:testdb;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
		"spring.datasource.driverClassName=org.h2.Driver",
		"spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
		"spring.jpa.hibernate.ddl-auto=create-drop"
})
class TableGroupRepositoryTest {
	
	@Autowired
	private TableGroupRepository tableGroupRepository;
	
	@Autowired
	private CityRepository cityRepository;
	
	@Autowired
	private DistrictRepository districtRepository;
	
	@Autowired
	private NeighborhoodRepository neighborhoodRepository;
	
	private City createAndSaveCity(String name) {
		City city = new City();
		city.setName(name);
		return cityRepository.save(city);
	}
	
	private District createAndSaveDistrict(String name, City city) {
		District district = new District();
		district.setName(name);
		district.setCity(city);
		return districtRepository.save(district);
	}
	
	private Neighborhood createAndSaveNeighborhood(String name, District district) {
		Neighborhood neighborhood = new Neighborhood();
		neighborhood.setName(name);
		neighborhood.setDistrict(district);
		return neighborhoodRepository.save(neighborhood);
	}
	
	private TableGroup createTableGroup(
			City city,
			District district,
			Neighborhood neighborhood,
			TableGroupStatus status,
			LocalDateTime expiresAt
	) {
		TableGroup group = TableGroup.builder()
		                             .ownerId(UUID.randomUUID())
		                             .venueName("Test Venue")
		                             .venueId(null)
		                             .maxPersonCount(4)
		                             .genderPrefs(List.of("MALE", "FEMALE"))
		                             .ageMin(20)
		                             .ageMax(30)
		                             .startAt(LocalDateTime.now().minusHours(1))
		                             .expiresAt(expiresAt)
		                             .status(status)
		                             .build();
		
		group.setCity(city);
		group.setDistrict(district);
		group.setNeighborhood(neighborhood);
		
		return tableGroupRepository.save(group);
	}
	
	@Test
	void listActiveByCityDistrictNeighborhood_whenMultipleGroups_shouldFilterByLocationStatusAndExpiry() {
		// given
		LocalDateTime now = LocalDateTime.now();
		
		City city1 = createAndSaveCity("Ankara");
		City city2 = createAndSaveCity("Istanbul");
		
		District district1 = createAndSaveDistrict("Cankaya", city1);
		District district2 = createAndSaveDistrict("Kadikoy", city2);
		
		Neighborhood n1 = createAndSaveNeighborhood("Tunalı", district1);
		Neighborhood n2 = createAndSaveNeighborhood("Moda", district2);
		
		// Bu dönmeli: city1 + district1 + n1 + ACTIVE + expiresAt future
		TableGroup g1 = createTableGroup(
				city1,
				district1,
				n1,
				TableGroupStatus.ACTIVE,
				now.plusHours(2)
		);
		
		// Aynı city/district ama başka neighborhood -> bu testte çağıracağımız metotta dönmeyecek
		TableGroup g2 = createTableGroup(
				city1,
				district1,
				null,
				TableGroupStatus.ACTIVE,
				now.plusHours(3)
		);
		
		// expiresAt geçmiş -> dönmemeli
		TableGroup g3 = createTableGroup(
				city1,
				district1,
				n1,
				TableGroupStatus.ACTIVE,
				now.minusHours(1)
		);
		
		// farklı city -> dönmemeli
		TableGroup g4 = createTableGroup(
				city2,
				district2,
				n2,
				TableGroupStatus.ACTIVE,
				now.plusHours(4)
		);
		
		Pageable pageable = PageRequest.of(0, 10);
		
		// when: city + district + neighborhood + status + expiresAfter
		Page<TableGroup> page = tableGroupRepository
				.findByCityIdAndDistrictIdAndNeighborhoodIdAndStatusAndExpiresAtAfter(
						city1.getId(),
						district1.getId(),
						n1.getId(),
						TableGroupStatus.ACTIVE,
						now,
						pageable
				);
		
		// then
		assertThat(page.getTotalElements()).isEqualTo(1);
		assertThat(page.getContent())
				.extracting(TableGroup::getId)
				.containsExactly(g1.getId());
	}
	
	@Test
	void listActiveByCityAndDistrict_whenNeighborhoodNull_shouldIgnoreNeighborhoodFilter() {
		// given
		LocalDateTime now = LocalDateTime.now();
		
		City city = createAndSaveCity("Ankara");
		District district = createAndSaveDistrict("Cankaya", city);
		
		Neighborhood n1 = createAndSaveNeighborhood("Tunalı", district);
		Neighborhood n2 = createAndSaveNeighborhood("Bahçelievler", district);
		
		// Aynı city/district/neighborhood1
		TableGroup g1 = createTableGroup(
				city,
				district,
				n1,
				TableGroupStatus.ACTIVE,
				now.plusHours(1)
		);
		
		// Aynı city/district/neighborhood2
		TableGroup g2 = createTableGroup(
				city,
				district,
				n2,
				TableGroupStatus.ACTIVE,
				now.plusHours(2)
		);
		
		// expiresAt geçmiş -> dönmemeli
		TableGroup g3 = createTableGroup(
				city,
				district,
				n1,
				TableGroupStatus.ACTIVE,
				now.minusHours(1)
		);
		
		Pageable pageable = PageRequest.of(0, 10);
		
		// when: sadece city + district + status + expiresAfter
		Page<TableGroup> page = tableGroupRepository
				.findByCityIdAndDistrictIdAndStatusAndExpiresAtAfter(
						city.getId(),
						district.getId(),
						TableGroupStatus.ACTIVE,
						now,
						pageable
				);
		
		// then
		assertThat(page.getTotalElements()).isEqualTo(2);
		assertThat(page.getContent())
				.extracting(TableGroup::getId)
				.containsExactlyInAnyOrder(g1.getId(), g2.getId());
	}
	
	@Test
	void listActiveByCity_whenOnlyCityProvided_shouldReturnAllActiveFutureGroupsInCity() {
		// given
		LocalDateTime now = LocalDateTime.now();
		
		City city = createAndSaveCity("Ankara");
		City otherCity = createAndSaveCity("Izmir");
		
		District district = createAndSaveDistrict("Cankaya", city);
		
		Neighborhood n1 = createAndSaveNeighborhood("Tunalı", district);
		
		// city: Ankara, ACTIVE, future
		TableGroup g1 = createTableGroup(
				city,
				district,
				n1,
				TableGroupStatus.ACTIVE,
				now.plusHours(1)
		);
		
		// city: Ankara, INACTIVE, future -> donmemeli
		TableGroup g2 = createTableGroup(
				city,
				district,
				n1,
				TableGroupStatus.INACTIVE,
				now.plusHours(2)
		);
		
		// city: Izmir, ACTIVE, future -> donmemeli
		TableGroup g3 = createTableGroup(
				otherCity,
				null,
				null,
				TableGroupStatus.ACTIVE,
				now.plusHours(3)
		);
		
		Pageable pageable = PageRequest.of(0, 10);
		
		// when
		Page<TableGroup> page = tableGroupRepository
				.findByCityIdAndStatusAndExpiresAtAfter(
						city.getId(),
						TableGroupStatus.ACTIVE,
						now,
						pageable
				);
		
		// then
		assertThat(page.getTotalElements()).isEqualTo(1);
		assertThat(page.getContent())
				.extracting(TableGroup::getId)
				.containsExactly(g1.getId());
	}
	
	@Test
	void findByStatusAndExpiresAtBefore_whenExpiredActivesExist_shouldReturnThem() {
		// given
		LocalDateTime now = LocalDateTime.now();
		
		City city = createAndSaveCity("Ankara");
		District district = createAndSaveDistrict("Cankaya", city);
		
		Neighborhood n1 = createAndSaveNeighborhood("Tunalı", district);
		
		// ACTIVE + suresi geçmiş -> dönmeli
		TableGroup expired1 = createTableGroup(
				city,
				district,
				n1,
				TableGroupStatus.ACTIVE,
				now.minusHours(2)
		);
		
		TableGroup expired2 = createTableGroup(
				city,
				district,
				n1,
				TableGroupStatus.ACTIVE,
				now.minusMinutes(30)
		);
		
		// ACTIVE + future -> dönmemeli
		TableGroup futureActive = createTableGroup(
				city,
				district,
				n1,
				TableGroupStatus.ACTIVE,
				now.plusHours(1)
		);
		
		// INACTIVE + geçmiş -> dönmemeli (status filtresi var)
		TableGroup inactiveExpired = createTableGroup(
				city,
				district,
				n1,
				TableGroupStatus.INACTIVE,
				now.minusHours(3)
		);
		
		// when
		List<TableGroup> expiredActives =
				tableGroupRepository.findByStatusAndExpiresAtBefore(
						TableGroupStatus.ACTIVE,
						now
				);
		
		// then
		assertThat(expiredActives)
				.extracting(TableGroup::getId)
				.containsExactlyInAnyOrder(expired1.getId(), expired2.getId())
				.doesNotContain(futureActive.getId(), inactiveExpired.getId());
	}
}