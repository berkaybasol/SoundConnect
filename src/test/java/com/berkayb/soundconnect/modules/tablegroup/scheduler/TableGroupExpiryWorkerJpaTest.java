package com.berkayb.soundconnect.modules.tablegroup.scheduler;

import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.repository.CityRepository;
import com.berkayb.soundconnect.modules.tablegroup.chat.cache.TableGroupChatUnreadHelper;
import com.berkayb.soundconnect.modules.tablegroup.entity.TableGroup;
import com.berkayb.soundconnect.modules.tablegroup.entity.TableGroupParticipant;
import com.berkayb.soundconnect.modules.tablegroup.enums.ParticipantStatus;
import com.berkayb.soundconnect.modules.tablegroup.enums.TableGroupStatus;
import com.berkayb.soundconnect.modules.tablegroup.game.service.TableGroupGameLifecycleService;
import com.berkayb.soundconnect.modules.tablegroup.notification.outbox.TableGroupNotificationOutboxService;
import com.berkayb.soundconnect.modules.tablegroup.observability.TableGroupMetrics;
import com.berkayb.soundconnect.modules.tablegroup.repository.TableGroupRepository;
import com.berkayb.soundconnect.modules.tablegroup.support.TableGroupEntityFinder;
import com.berkayb.soundconnect.shared.config.JpaAuditingConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@Import({JpaAuditingConfig.class, TableGroupEntityFinder.class, TableGroupExpiryWorker.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TableGroupExpiryWorkerJpaTest {
	@Container
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
	}

	@Autowired TableGroupExpiryWorker worker;
	@Autowired TableGroupRepository tableGroupRepository;
	@Autowired CityRepository cityRepository;
	@MockitoBean TableGroupNotificationOutboxService notificationOutboxService;
	@MockitoBean TableGroupChatUnreadHelper unreadHelper;
	@MockitoBean TableGroupGameLifecycleService gameLifecycleService;
	@MockitoBean TableGroupMetrics metrics;

	@Test
	void failedAggregateRollsBackWithoutPreventingTheNextAggregateFromCommitting() {
		Instant scanTime = Instant.parse("2026-09-02T10:00:00Z");
		City city = new City();
		city.setName("Expiry Worker City");
		city = cityRepository.save(city);
		UUID poisonPendingId = UUID.randomUUID();
		TableGroup poisonCandidate = expiredGroup(city, scanTime);
		poisonCandidate.getParticipants().add(TableGroupParticipant.builder()
				.userId(poisonPendingId)
				.status(ParticipantStatus.PENDING)
				.joinedAt(scanTime.minusSeconds(60))
				.build());
		TableGroup poison = tableGroupRepository.saveAndFlush(poisonCandidate);
		TableGroup healthy = tableGroupRepository.saveAndFlush(expiredGroup(city, scanTime));
		doAnswer(invocation -> {
			TableGroup group = invocation.getArgument(0);
			if (poison.getId().equals(group.getId())) {
				throw new IllegalStateException("poison aggregate");
			}
			return null;
		}).when(gameLifecycleService).tableClosed(any(TableGroup.class), eq("TABLE_EXPIRED"));

		assertThatThrownBy(() -> worker.expireIfDue(poison.getId(), scanTime))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("poison aggregate");
		assertThat(worker.expireIfDue(healthy.getId(), scanTime)).isTrue();

		TableGroup rolledBack = tableGroupRepository.findById(poison.getId()).orElseThrow();
		assertThat(rolledBack.getStatus()).isEqualTo(TableGroupStatus.ACTIVE);
		assertThat(tableGroupRepository.findParticipantStatus(poison.getId(), poisonPendingId))
				.contains(ParticipantStatus.PENDING);
		assertThat(tableGroupRepository.findById(healthy.getId()).orElseThrow().getStatus())
				.isEqualTo(TableGroupStatus.INACTIVE);
		verify(unreadHelper, never()).clearAllUnreadForTableGroup(poison.getId());
		verify(unreadHelper).clearAllUnreadForTableGroup(healthy.getId());
		verify(metrics, times(1)).expired();
	}

	private TableGroup expiredGroup(City city, Instant scanTime) {
		TableGroup group = TableGroup.builder()
				.ownerId(UUID.randomUUID())
				.createRequestKey(UUID.randomUUID())
				.venueName("Expiry Venue")
				.description("Expiry table")
				.maxPersonCount(4)
				.genderPrefs(new ArrayList<>(List.of("MALE", "FEMALE")))
				.ageMin(20)
				.ageMax(40)
				.startAt(scanTime.minusSeconds(3_600))
				.meetingAt(scanTime.minusSeconds(1))
				.expiresAt(scanTime.minusSeconds(1))
				.status(TableGroupStatus.ACTIVE)
				.participants(new HashSet<>())
				.build();
		group.setCity(city);
		return group;
	}
}
