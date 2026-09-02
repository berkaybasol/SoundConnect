package com.berkayb.soundconnect.modules.tablegroup.repository;

import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.repository.CityRepository;
import com.berkayb.soundconnect.modules.location.repository.DistrictRepository;
import com.berkayb.soundconnect.modules.location.repository.NeighborhoodRepository;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.profile.StudioProfile.repository.StudioProfileRepository;
import com.berkayb.soundconnect.modules.profile.shared.avatar.PersonalProfileAvatarBatchResolver;
import com.berkayb.soundconnect.modules.tablegroup.abuse.TableGroupRateLimitGuard;
import com.berkayb.soundconnect.modules.tablegroup.chat.cache.TableGroupChatUnreadHelper;
import com.berkayb.soundconnect.modules.tablegroup.entity.TableGroup;
import com.berkayb.soundconnect.modules.tablegroup.entity.TableGroupParticipant;
import com.berkayb.soundconnect.modules.tablegroup.chat.entity.TableGroupMessage;
import com.berkayb.soundconnect.modules.tablegroup.chat.enums.MessageType;
import com.berkayb.soundconnect.modules.tablegroup.chat.repository.TableGroupMessageRepository;
import com.berkayb.soundconnect.modules.tablegroup.enums.ParticipantStatus;
import com.berkayb.soundconnect.modules.tablegroup.enums.TableGroupStatus;
import com.berkayb.soundconnect.modules.tablegroup.game.service.TableGroupGameLifecycleService;
import com.berkayb.soundconnect.modules.tablegroup.mapper.TableGroupMapper;
import com.berkayb.soundconnect.modules.tablegroup.notification.outbox.TableGroupNotificationOutboxService;
import com.berkayb.soundconnect.modules.tablegroup.observability.TableGroupMetrics;
import com.berkayb.soundconnect.modules.tablegroup.service.TableGroupServiceImpl;
import com.berkayb.soundconnect.modules.tablegroup.support.TableGroupEntityFinder;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.modules.venue.repository.VenueRepository;
import com.berkayb.soundconnect.shared.config.JpaAuditingConfig;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DataJpaTest
@Import(JpaAuditingConfig.class)
@Testcontainers(disabledWithoutDocker = true)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class TableGroupCapacityConcurrencyPostgresTest {

	@Container
	static final PostgreSQLContainer<?> POSTGRES =
			new PostgreSQLContainer<>("postgres:16.4-alpine");

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
	}

	@Autowired private TableGroupRepository tableGroupRepository;
	@Autowired private CityRepository cityRepository;
	@Autowired private PlatformTransactionManager transactionManager;
	@Autowired private TableGroupMessageRepository messageRepository;
	@Autowired private UserRepository userRepository;

	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	void sameClientMessageKey_shouldSerializeAcrossTransactions() throws Exception {
		UUID tableGroupId = UUID.randomUUID();
		UUID senderId = UUID.randomUUID();
		UUID clientMessageId = UUID.randomUUID();
		CountDownLatch firstHasKeyLock = new CountDownLatch(1);
		CountDownLatch allowFirstCommit = new CountDownLatch(1);
		CountDownLatch secondIsWaiting = new CountDownLatch(1);
		CountDownLatch secondHasKeyLock = new CountDownLatch(1);
		TransactionTemplate transaction = new TransactionTemplate(transactionManager);

		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			Future<Void> first = executor.submit(() -> {
				transaction.executeWithoutResult(status -> {
					assertThat(messageRepository.acquireClientMessageKeyLock(
							tableGroupId, senderId, clientMessageId)).isEqualTo(1);
					firstHasKeyLock.countDown();
					await(allowFirstCommit);
				});
				return null;
			});
			Future<Integer> second = executor.submit(() -> {
				if (!firstHasKeyLock.await(10, TimeUnit.SECONDS)) {
					throw new IllegalStateException("first key lock timeout");
				}
				return transaction.execute(status -> {
					secondIsWaiting.countDown();
					int result = messageRepository.acquireClientMessageKeyLock(
							tableGroupId, senderId, clientMessageId);
					secondHasKeyLock.countDown();
					return result;
				});
			});

			try {
				assertThat(firstHasKeyLock.await(10, TimeUnit.SECONDS)).isTrue();
				assertThat(secondIsWaiting.await(10, TimeUnit.SECONDS)).isTrue();
				assertThat(secondHasKeyLock.await(300, TimeUnit.MILLISECONDS)).isFalse();
			} finally {
				allowFirstCommit.countDown();
			}

			first.get(15, TimeUnit.SECONDS);
			assertThat(second.get(15, TimeUnit.SECONDS)).isEqualTo(1);
			assertThat(secondHasKeyLock.getCount()).isZero();
		}
	}

	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	void concurrentApprovals_shouldSerializeOnAggregateAndNeverOverbook() throws Exception {
		UUID ownerId = UUID.randomUUID();
		UUID firstApplicantId = UUID.randomUUID();
		UUID secondApplicantId = UUID.randomUUID();
		City city = cityRepository.saveAndFlush(City.builder().name("Concurrency City").build());

		TableGroup group = TableGroup.builder()
				.ownerId(ownerId)
				.createRequestKey(UUID.randomUUID())
				.venueName("Concurrency Venue")
				.description("Concurrency table")
				.maxPersonCount(2)
				.genderPrefs(List.of("MALE", "FEMALE"))
				.ageMin(20)
				.ageMax(40)
				.startAt(Instant.now())
				.meetingAt(Instant.now().plusSeconds(1800))
				.expiresAt(Instant.now().plusSeconds(3600))
				.status(TableGroupStatus.ACTIVE)
				.city(city)
				.participants(new HashSet<>(List.of(
						participant(ownerId, ParticipantStatus.ACCEPTED),
						participant(firstApplicantId, ParticipantStatus.PENDING),
						participant(secondApplicantId, ParticipantStatus.PENDING)
				)))
				.build();
		UUID groupId = tableGroupRepository.saveAndFlush(group).getId();

		CountDownLatch start = new CountDownLatch(1);
		TransactionTemplate transaction = new TransactionTemplate(transactionManager);
		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			Future<Boolean> first = executor.submit(() -> approveIfSeatAvailable(
					transaction, start, groupId, firstApplicantId));
			Future<Boolean> second = executor.submit(() -> approveIfSeatAvailable(
					transaction, start, groupId, secondApplicantId));
			start.countDown();

			assertThat(List.of(
					first.get(15, TimeUnit.SECONDS),
					second.get(15, TimeUnit.SECONDS)
			)).containsExactlyInAnyOrder(true, false);
		}

		Long acceptedCount = transaction.execute(status -> tableGroupRepository.findById(groupId)
				.orElseThrow()
				.getParticipants().stream()
				.filter(participant -> participant.getStatus() == ParticipantStatus.ACCEPTED)
				.count());
		assertThat(acceptedCount).isEqualTo(2L);
	}

	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	void crossApprovals_shouldLockTargetAndOwnedTablesInStableOrderWithoutDeadlock() throws Exception {
		UUID firstOwnerId = saveUser("cross_first").getId();
		UUID secondOwnerId = saveUser("cross_second").getId();
		City city = cityRepository.saveAndFlush(City.builder().name("Cross Join City").build());
		UUID firstTableId = tableGroupRepository.saveAndFlush(TableGroup.builder()
				.ownerId(firstOwnerId)
				.createRequestKey(UUID.randomUUID())
				.venueName("First Venue")
				.description("First table")
				.maxPersonCount(2)
				.genderPrefs(List.of("MALE", "FEMALE"))
				.ageMin(20).ageMax(40)
				.startAt(Instant.now()).meetingAt(Instant.now().plusSeconds(1800))
				.expiresAt(Instant.now().plusSeconds(3600))
				.status(TableGroupStatus.ACTIVE).city(city)
				.participants(new HashSet<>(List.of(
						participant(firstOwnerId, ParticipantStatus.ACCEPTED),
						participant(secondOwnerId, ParticipantStatus.PENDING))))
				.build()).getId();
		UUID secondTableId = tableGroupRepository.saveAndFlush(TableGroup.builder()
				.ownerId(secondOwnerId)
				.createRequestKey(UUID.randomUUID())
				.venueName("Second Venue")
				.description("Second table")
				.maxPersonCount(2)
				.genderPrefs(List.of("MALE", "FEMALE"))
				.ageMin(20).ageMax(40)
				.startAt(Instant.now()).meetingAt(Instant.now().plusSeconds(1800))
				.expiresAt(Instant.now().plusSeconds(3600))
				.status(TableGroupStatus.ACTIVE).city(city)
				.participants(new HashSet<>(List.of(
						participant(secondOwnerId, ParticipantStatus.ACCEPTED),
						participant(firstOwnerId, ParticipantStatus.PENDING))))
				.build()).getId();

		CountDownLatch start = new CountDownLatch(1);
		TransactionTemplate transaction = new TransactionTemplate(transactionManager);
		TableGroupServiceImpl service = actualLifecycleService();
		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			Future<Boolean> first = executor.submit(() -> approveWithProductionService(
					transaction, start, service, secondOwnerId, secondTableId, firstOwnerId));
			Future<Boolean> second = executor.submit(() -> approveWithProductionService(
					transaction, start, service, firstOwnerId, firstTableId, secondOwnerId));
			start.countDown();

			assertThat(List.of(
					first.get(15, TimeUnit.SECONDS),
					second.get(15, TimeUnit.SECONDS)
			)).containsExactlyInAnyOrder(true, false);
		}

		List<TableGroupStatus> statuses = transaction.execute(status -> List.of(
				tableGroupRepository.findById(firstTableId).orElseThrow().getStatus(),
				tableGroupRepository.findById(secondTableId).orElseThrow().getStatus()));
		assertThat(statuses).containsExactlyInAnyOrder(
				TableGroupStatus.ACTIVE, TableGroupStatus.CANCELLED);
	}

	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	void sendAttemptStartingDuringCancellation_shouldObserveCancelledStateAndPersistNothing() throws Exception {
		UUID ownerId = UUID.randomUUID();
		City city = cityRepository.saveAndFlush(City.builder().name("Chat Lock City").build());
		TableGroup group = TableGroup.builder()
				.ownerId(ownerId)
				.createRequestKey(UUID.randomUUID())
				.venueName("Chat Lock Venue")
				.description("Chat lock table")
				.maxPersonCount(2)
				.genderPrefs(List.of("MALE", "FEMALE"))
				.ageMin(20)
				.ageMax(40)
				.startAt(Instant.now())
				.meetingAt(Instant.now().plusSeconds(1800))
				.expiresAt(Instant.now().plusSeconds(3600))
				.status(TableGroupStatus.ACTIVE)
				.city(city)
				.participants(new HashSet<>(List.of(participant(ownerId, ParticipantStatus.ACCEPTED))))
				.build();
		UUID groupId = tableGroupRepository.saveAndFlush(group).getId();

		CountDownLatch cancelHasLock = new CountDownLatch(1);
		CountDownLatch sendIsAttemptingLock = new CountDownLatch(1);
		CountDownLatch allowCancelCommit = new CountDownLatch(1);
		TransactionTemplate transaction = new TransactionTemplate(transactionManager);
		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			Future<Void> cancellation = executor.submit(() -> {
				transaction.executeWithoutResult(status -> {
					TableGroup locked = tableGroupRepository.findByIdForUpdate(groupId).orElseThrow();
					locked.setStatus(TableGroupStatus.CANCELLED);
					tableGroupRepository.saveAndFlush(locked);
					cancelHasLock.countDown();
					await(allowCancelCommit);
				});
				return null;
			});
			Future<Boolean> send = executor.submit(() -> {
				cancelHasLock.await(10, TimeUnit.SECONDS);
				sendIsAttemptingLock.countDown();
				return transaction.execute(status -> {
					TableGroup locked = tableGroupRepository.findByIdForUpdate(groupId).orElseThrow();
					if (locked.getStatus() != TableGroupStatus.ACTIVE) return false;
					TableGroupMessage message = TableGroupMessage.builder()
							.tableGroupId(groupId).senderId(ownerId).content("late")
							.messageType(MessageType.TEXT).build();
					message.setCreatedAt(java.time.LocalDateTime.now());
					messageRepository.saveAndFlush(message);
					return true;
				});
			});

			assertThat(sendIsAttemptingLock.await(10, TimeUnit.SECONDS)).isTrue();
			allowCancelCommit.countDown();
			cancellation.get(15, TimeUnit.SECONDS);
			assertThat(send.get(15, TimeUnit.SECONDS)).isFalse();
		}

		assertThat(messageRepository.countByTableGroupIdAndDeletedAtIsNull(groupId)).isZero();
	}

	private void await(CountDownLatch latch) {
		try {
			if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("latch timeout");
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(exception);
		}
	}

	private boolean approveIfSeatAvailable(
			TransactionTemplate transaction,
			CountDownLatch start,
			UUID groupId,
			UUID applicantId
	) throws Exception {
		start.await();
		Boolean approved = transaction.execute(status -> {
			TableGroup locked = tableGroupRepository.findByIdForUpdate(groupId).orElseThrow();
			long accepted = locked.getParticipants().stream()
					.filter(participant -> participant.getStatus() == ParticipantStatus.ACCEPTED)
					.count();
			if (accepted >= locked.getMaxPersonCount()) {
				return false;
			}
			TableGroupParticipant applicant = locked.getParticipants().stream()
					.filter(participant -> participant.getUserId().equals(applicantId))
					.findFirst()
					.orElseThrow();
			applicant.setStatus(ParticipantStatus.ACCEPTED);
			tableGroupRepository.saveAndFlush(locked);
			return true;
		});
		return Boolean.TRUE.equals(approved);
	}

	private boolean approveWithProductionService(
			TransactionTemplate transaction,
			CountDownLatch start,
			TableGroupServiceImpl service,
			UUID targetOwnerId,
			UUID targetTableId,
			UUID applicantId
	) throws Exception {
		start.await();
		try {
			transaction.executeWithoutResult(status -> service.approveJoinRequest(
					targetOwnerId, targetTableId, applicantId));
			return true;
		} catch (SoundConnectException expectedConcurrentLoss) {
			return false;
		}
	}

	private TableGroupServiceImpl actualLifecycleService() {
		return new TableGroupServiceImpl(
				mock(TableGroupNotificationOutboxService.class),
				tableGroupRepository,
				mock(TableGroupMapper.class),
				cityRepository,
				mock(DistrictRepository.class),
				mock(NeighborhoodRepository.class),
				new TableGroupEntityFinder(tableGroupRepository, messageRepository),
				messageRepository,
				mock(TableGroupChatUnreadHelper.class),
				userRepository,
				mock(PersonalProfileAvatarBatchResolver.class),
				mock(StudioProfileRepository.class),
				mock(VenueRepository.class),
				mock(MediaAssetService.class),
				mock(TableGroupRateLimitGuard.class),
				mock(TableGroupMetrics.class),
				mock(TableGroupGameLifecycleService.class),
				mock(com.berkayb.soundconnect.modules.tablegroup.scheduler.TableGroupExpiryWorker.class)
		);
	}

	private User saveUser(String prefix) {
		String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
		return userRepository.saveAndFlush(User.builder()
				.username(prefix + "_" + suffix)
				.password("secret")
				.email(prefix + "_" + suffix + "@example.test")
				.build());
	}

	private TableGroupParticipant participant(UUID userId, ParticipantStatus status) {
		return TableGroupParticipant.builder()
				.userId(userId)
				.joinedAt(Instant.now())
				.status(status)
				.build();
	}
}
