package com.berkayb.soundconnect.modules.tablegroup.repository;

import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.entity.District;
import com.berkayb.soundconnect.modules.location.entity.Neighborhood;
import com.berkayb.soundconnect.modules.location.repository.CityRepository;
import com.berkayb.soundconnect.modules.location.repository.DistrictRepository;
import com.berkayb.soundconnect.modules.location.repository.NeighborhoodRepository;
import com.berkayb.soundconnect.modules.tablegroup.entity.TableGroup;
import com.berkayb.soundconnect.modules.tablegroup.entity.TableGroupParticipant;
import com.berkayb.soundconnect.modules.tablegroup.enums.ParticipantStatus;
import com.berkayb.soundconnect.modules.tablegroup.enums.TableGroupStatus;
import com.berkayb.soundconnect.modules.tablegroup.game.entity.TableGroupGame;
import com.berkayb.soundconnect.modules.tablegroup.game.enums.TableGroupGameMode;
import com.berkayb.soundconnect.modules.tablegroup.game.enums.TableGroupGamePhase;
import com.berkayb.soundconnect.modules.tablegroup.game.enums.TableGroupGameStatus;
import com.berkayb.soundconnect.modules.tablegroup.game.enums.TableGroupGameTopic;
import com.berkayb.soundconnect.modules.tablegroup.game.repository.TableGroupGameRepository;
import com.berkayb.soundconnect.shared.config.JpaAuditingConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(SpringExtension.class)
@Import(JpaAuditingConfig.class)
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
	private TableGroupGameRepository gameRepository;
	
	@Autowired
	private CityRepository cityRepository;
	
	@Autowired
	private DistrictRepository districtRepository;
	
	@Autowired
	private NeighborhoodRepository neighborhoodRepository;

	@Autowired
	private EntityManager entityManager;

	@Test
	void lifecycleMutationQuery_shouldDeclarePessimisticWriteLock() throws NoSuchMethodException {
		Lock lock = TableGroupRepository.class
				.getMethod("findByIdForUpdate", UUID.class)
				.getAnnotation(Lock.class);

		assertThat(lock).isNotNull();
		assertThat(lock.value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
	}

	@Test
	void tableGroup_shouldKeepOptimisticVersionAsSecondLineOfDefense() throws NoSuchFieldException {
		assertThat(TableGroup.class.getDeclaredField("version").getAnnotation(Version.class)).isNotNull();
	}

	@Test
	void tableGroup_shouldRequireCreatedAtForDeterministicFeedOrdering() {
		AttributeOverride override = TableGroup.class.getAnnotation(AttributeOverride.class);

		assertThat(override).isNotNull();
		assertThat(override.name()).isEqualTo("createdAt");
		assertThat(override.column().nullable()).isFalse();
		assertThat(override.column().updatable()).isFalse();
	}

	@Test
	void tableGroup_shouldKeepTerminalHistoryDescriptionNullableAndBoundedAtRest()
			throws NoSuchFieldException {
		Column column = TableGroup.class.getDeclaredField("description").getAnnotation(Column.class);

		assertThat(column).isNotNull();
		assertThat(column.name()).isEqualTo("description");
		assertThat(column.nullable()).isTrue();
		assertThat(column.length()).isEqualTo(280);
	}

	@Test
	void tableGroup_shouldRequireMeetingAtAndCreateRequestKeyAtRest()
			throws NoSuchFieldException {
		Column meetingAt = TableGroup.class.getDeclaredField("meetingAt").getAnnotation(Column.class);
		Column createRequestKey = TableGroup.class.getDeclaredField("createRequestKey")
				.getAnnotation(Column.class);

		assertThat(meetingAt).isNotNull();
		assertThat(meetingAt.name()).isEqualTo("meeting_at");
		assertThat(meetingAt.nullable()).isFalse();
		assertThat(createRequestKey).isNotNull();
		assertThat(createRequestKey.name()).isEqualTo("create_request_key");
		assertThat(createRequestKey.nullable()).isFalse();
	}

	@Test
	void tableGroup_shouldDeclareOwnerLifecycleLookupIndex() {
		jakarta.persistence.Table table = TableGroup.class.getAnnotation(jakarta.persistence.Table.class);

		assertThat(table.indexes())
				.anySatisfy(index -> {
					assertThat(index.name()).isEqualTo("idx_tablegroup_owner_status_exp_id");
					assertThat(index.columnList()).isEqualTo("owner_id,status,expires_at,id");
				});
	}

	@Test
	void openAccessQuery_shouldAuthorizeOnlyOwnerOrAcceptedParticipantWithoutLoadingHistory() {
		City city = createAndSaveCity("Access City");
		TableGroup group = createTableGroup(
				city, null, null, TableGroupStatus.ACTIVE, Instant.now().plusSeconds(3600));
		UUID acceptedId = UUID.randomUUID();
		UUID pendingId = UUID.randomUUID();
		group.getParticipants().add(TableGroupParticipant.builder()
				.userId(acceptedId).status(ParticipantStatus.ACCEPTED).joinedAt(Instant.now()).build());
		group.getParticipants().add(TableGroupParticipant.builder()
				.userId(pendingId).status(ParticipantStatus.PENDING).joinedAt(Instant.now()).build());
		group = tableGroupRepository.saveAndFlush(group);

		assertThat(tableGroupRepository.countOpenAccess(
				group.getId(), group.getOwnerId(), TableGroupStatus.ACTIVE,
				ParticipantStatus.ACCEPTED, Instant.now())).isEqualTo(1);
		assertThat(tableGroupRepository.countOpenAccess(
				group.getId(), acceptedId, TableGroupStatus.ACTIVE,
				ParticipantStatus.ACCEPTED, Instant.now())).isEqualTo(1);
		assertThat(tableGroupRepository.countOpenAccess(
				group.getId(), pendingId, TableGroupStatus.ACTIVE,
				ParticipantStatus.ACCEPTED, Instant.now())).isZero();
		assertThat(tableGroupRepository.findParticipantStatusForOwner(
				group.getId(), group.getOwnerId(), pendingId))
				.contains(ParticipantStatus.PENDING);
		assertThat(tableGroupRepository.findParticipantStatusForOwner(
				group.getId(), UUID.randomUUID(), pendingId)).isEmpty();
		assertThat(tableGroupRepository.findParticipantStatusForOwner(
				group.getId(), group.getOwnerId(), UUID.randomUUID())).isEmpty();
		assertThat(tableGroupRepository.findParticipantStatus(group.getId(), acceptedId))
				.contains(ParticipantStatus.ACCEPTED);
		assertThat(tableGroupRepository.findParticipantStatus(group.getId(), UUID.randomUUID()))
				.isEmpty();
		assertThat(tableGroupRepository.findStatusById(group.getId()))
				.contains(TableGroupStatus.ACTIVE);
	}

	@Test
	void gameAssociationQuery_shouldReturnOnlyTheOwningTableId() {
		Instant now = Instant.now();
		TableGroup group = createTableGroup(
				createAndSaveCity("Game Association City"),
				null,
				null,
				TableGroupStatus.ACTIVE,
				now.plusSeconds(3_600)
		);
		TableGroupGame game = gameRepository.saveAndFlush(TableGroupGame.builder()
				.version(0)
				.revision(1)
				.tableGroupId(group.getId())
				.createdBy(group.getOwnerId())
				.createdByUsername("owner")
				.createRequestId(UUID.randomUUID())
				.topic(TableGroupGameTopic.WHO_PAYS)
				.mode(TableGroupGameMode.DICE)
				.status(TableGroupGameStatus.LOBBY)
				.phase(TableGroupGamePhase.LOBBY)
				.roundNumber(0)
				.joinDeadlineAt(now.plusSeconds(180))
				.build());

		assertThat(gameRepository.findTableGroupIdById(game.getId()))
				.contains(group.getId());
		assertThat(gameRepository.findTableGroupIdById(UUID.randomUUID())).isEmpty();
	}

	@Test
	void findOpenIdsByOwner_shouldIgnoreExpiredAndTerminalTables() {
		Instant now = Instant.now();
		UUID ownerId = UUID.randomUUID();
		City city = createAndSaveCity("Owner Lifecycle City");
		TableGroup firstOpen = createTableGroup(
				city, null, null, TableGroupStatus.ACTIVE, now.plusSeconds(3600));
		TableGroup secondOpen = createTableGroup(
				city, null, null, TableGroupStatus.ACTIVE, now.plusSeconds(7200));
		TableGroup schedulerLagged = createTableGroup(
				city, null, null, TableGroupStatus.ACTIVE, now.minusSeconds(1));
		TableGroup cancelled = createTableGroup(
				city, null, null, TableGroupStatus.CANCELLED, now.plusSeconds(7200));
		List.of(firstOpen, secondOpen, schedulerLagged, cancelled)
				.forEach(group -> group.setOwnerId(ownerId));
		tableGroupRepository.saveAllAndFlush(List.of(
				firstOpen, secondOpen, schedulerLagged, cancelled));

		assertThat(tableGroupRepository.findOpenIdsByOwner(
				ownerId, TableGroupStatus.ACTIVE, now))
				.containsExactlyInAnyOrder(firstOpen.getId(), secondOpen.getId())
				.doesNotContain(schedulerLagged.getId(), cancelled.getId());
	}
	
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
			Instant expiresAt
	) {
		TableGroup group = TableGroup.builder()
		                             .ownerId(UUID.randomUUID())
		                             .createRequestKey(UUID.randomUUID())
		                             .venueName("Test Venue")
		                             .venueId(null)
		                             .description("Test table")
		                             .maxPersonCount(4)
		                             .genderPrefs(new java.util.ArrayList<>(List.of("MALE", "FEMALE")))
		                             .ageMin(20)
		                             .ageMax(30)
		                             .startAt(Instant.now().minusSeconds(3600))
		                             .meetingAt(expiresAt)
		                             .expiresAt(expiresAt)
		                             .status(status)
		                             .build();
		
		group.setCity(city);
		group.setDistrict(district);
		group.setNeighborhood(neighborhood);
		
		return tableGroupRepository.save(group);
	}

	private void setPersistedCreatedAt(UUID tableGroupId, LocalDateTime createdAt) {
		entityManager.createNativeQuery("""
				update tbl_table_group
				set created_at = :createdAt
				where id = :tableGroupId
				""")
				.setParameter("createdAt", createdAt)
				.setParameter("tableGroupId", tableGroupId)
				.executeUpdate();
	}

	@Test
	void listGlobalActive_shouldFilterAcrossCitiesAndPageInStableNewestFirstOrder() {
		Instant now = Instant.now();
		LocalDateTime createdBase = LocalDateTime.of(2026, 8, 31, 10, 0);
		City ankara = createAndSaveCity("Global Ankara");
		City istanbul = createAndSaveCity("Global Istanbul");

		TableGroup older = createTableGroup(
				ankara, null, null, TableGroupStatus.ACTIVE,
				now.plusSeconds(3600));
		TableGroup newer = createTableGroup(
				istanbul, null, null, TableGroupStatus.ACTIVE,
				now.plusSeconds(7200));
		TableGroup expired = createTableGroup(
				ankara, null, null, TableGroupStatus.ACTIVE,
				now.minusSeconds(1));
		TableGroup inactive = createTableGroup(
				istanbul, null, null, TableGroupStatus.INACTIVE,
				now.plusSeconds(10800));
		tableGroupRepository.flush();
		// @CreatedDate intentionally owns normal inserts. Set deterministic audit
		// values at the database boundary so this ordering assertion cannot fall
		// through to the random UUID tie-break when two inserts share a timestamp.
		setPersistedCreatedAt(older.getId(), createdBase);
		setPersistedCreatedAt(newer.getId(), createdBase.plusMinutes(1));
		entityManager.clear();

		Pageable firstPage = PageRequest.of(0, 1, Sort.by(
				Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
		Pageable secondPage = PageRequest.of(1, 1, firstPage.getSort());

		Page<TableGroup> first = tableGroupRepository.findByStatusAndExpiresAtAfter(
				TableGroupStatus.ACTIVE, now, firstPage);
		Page<TableGroup> second = tableGroupRepository.findByStatusAndExpiresAtAfter(
				TableGroupStatus.ACTIVE, now, secondPage);

		assertThat(first.getTotalElements()).isEqualTo(2);
		assertThat(first.getContent()).extracting(TableGroup::getId)
				.containsExactly(newer.getId());
		assertThat(second.getContent()).extracting(TableGroup::getId)
				.containsExactly(older.getId());
		assertThat(first.getContent()).extracting(TableGroup::getId)
				.doesNotContain(expired.getId(), inactive.getId());
	}

	@Test
	void findActiveAccessibleByUser_shouldReturnOwnedAndAcceptedTablesOnlyOnce() {
		Instant now = Instant.now();
		UUID viewerId = UUID.randomUUID();
		City city = createAndSaveCity("Member Inbox City");

		TableGroup owned = createTableGroup(
				city, null, null, TableGroupStatus.ACTIVE, now.plusSeconds(3600));
		owned.setOwnerId(viewerId);
		TableGroup accepted = createTableGroup(
				city, null, null, TableGroupStatus.ACTIVE, now.plusSeconds(7200));
		accepted.getParticipants().add(TableGroupParticipant.builder()
				.userId(viewerId)
				.status(ParticipantStatus.ACCEPTED)
				.joinedAt(now)
				.build());
		TableGroup pending = createTableGroup(
				city, null, null, TableGroupStatus.ACTIVE, now.plusSeconds(7200));
		pending.getParticipants().add(TableGroupParticipant.builder()
				.userId(viewerId)
				.status(ParticipantStatus.PENDING)
				.joinedAt(now)
				.build());
		TableGroup expired = createTableGroup(
				city, null, null, TableGroupStatus.ACTIVE, now.minusSeconds(1));
		expired.setOwnerId(viewerId);
		TableGroup inactive = createTableGroup(
				city, null, null, TableGroupStatus.INACTIVE, now.plusSeconds(7200));
		inactive.setOwnerId(viewerId);
		tableGroupRepository.saveAllAndFlush(List.of(owned, accepted, pending, expired, inactive));
		entityManager.clear();

		Page<TableGroup> page = tableGroupRepository.findActiveAccessibleByUser(
				viewerId,
				TableGroupStatus.ACTIVE,
				ParticipantStatus.ACCEPTED,
				now,
				PageRequest.of(0, 10, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")))
		);

		assertThat(page.getTotalElements()).isEqualTo(2);
		assertThat(page.getContent()).extracting(TableGroup::getId)
				.containsExactlyInAnyOrder(owned.getId(), accepted.getId())
				.doesNotHaveDuplicates();
	}
	
	@Test
	void listActiveByCityDistrictNeighborhood_whenMultipleGroups_shouldFilterByLocationStatusAndExpiry() {
		// given
		Instant now = Instant.now();
		
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
				now.plusSeconds(7200)
		);
		
		// Aynı city/district ama başka neighborhood -> bu testte çağıracağımız metotta dönmeyecek
		TableGroup g2 = createTableGroup(
				city1,
				district1,
				null,
				TableGroupStatus.ACTIVE,
				now.plusSeconds(10800)
		);
		
		// expiresAt geçmiş -> dönmemeli
		TableGroup g3 = createTableGroup(
				city1,
				district1,
				n1,
				TableGroupStatus.ACTIVE,
				now.minusSeconds(3600)
		);
		
		// farklı city -> dönmemeli
		TableGroup g4 = createTableGroup(
				city2,
				district2,
				n2,
				TableGroupStatus.ACTIVE,
				now.plusSeconds(14400)
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
		Instant now = Instant.now();
		
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
				now.plusSeconds(3600)
		);
		
		// Aynı city/district/neighborhood2
		TableGroup g2 = createTableGroup(
				city,
				district,
				n2,
				TableGroupStatus.ACTIVE,
				now.plusSeconds(7200)
		);
		
		// expiresAt geçmiş -> dönmemeli
		TableGroup g3 = createTableGroup(
				city,
				district,
				n1,
				TableGroupStatus.ACTIVE,
				now.minusSeconds(3600)
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
		Instant now = Instant.now();
		
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
				now.plusSeconds(3600)
		);
		
		// city: Ankara, INACTIVE, future -> donmemeli
		TableGroup g2 = createTableGroup(
				city,
				district,
				n1,
				TableGroupStatus.INACTIVE,
				now.plusSeconds(7200)
		);
		
		// city: Izmir, ACTIVE, future -> donmemeli
		TableGroup g3 = createTableGroup(
				otherCity,
				null,
				null,
				TableGroupStatus.ACTIVE,
				now.plusSeconds(10800)
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
	void findExpiredIds_whenExpiredActivesExist_shouldReturnBoundedIdsInExpiryOrder() {
		// given
		Instant now = Instant.now();
		
		City city = createAndSaveCity("Ankara");
		District district = createAndSaveDistrict("Cankaya", city);
		
		Neighborhood n1 = createAndSaveNeighborhood("Tunalı", district);
		
		// ACTIVE + suresi geçmiş -> dönmeli
		TableGroup expired1 = createTableGroup(
				city,
				district,
				n1,
				TableGroupStatus.ACTIVE,
				now.minusSeconds(7200)
		);
		
		TableGroup expired2 = createTableGroup(
				city,
				district,
				n1,
				TableGroupStatus.ACTIVE,
				now.minusSeconds(1800)
		);
		
		// ACTIVE + future -> dönmemeli
		TableGroup futureActive = createTableGroup(
				city,
				district,
				n1,
				TableGroupStatus.ACTIVE,
				now.plusSeconds(3600)
		);
		
		// INACTIVE + geçmiş -> dönmemeli (status filtresi var)
		TableGroup inactiveExpired = createTableGroup(
				city,
				district,
				n1,
				TableGroupStatus.INACTIVE,
				now.minusSeconds(10800)
		);
		
		// when
		List<UUID> expiredIds =
				tableGroupRepository.findExpiredIds(
						TableGroupStatus.ACTIVE,
						now,
						PageRequest.of(0, 10)
				);
		
		// then
		assertThat(expiredIds)
				.containsExactly(expired1.getId(), expired2.getId())
				.doesNotContain(futureActive.getId(), inactiveExpired.getId());
	}
}
