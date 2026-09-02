package com.berkayb.soundconnect.modules.tablegroup.service;

import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.repository.*;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.profile.shared.avatar.PersonalProfileAvatarBatchResolver;
import com.berkayb.soundconnect.modules.profile.StudioProfile.repository.StudioProfileRepository;
import com.berkayb.soundconnect.modules.tablegroup.abuse.TableGroupRateLimitGuard;
import com.berkayb.soundconnect.modules.tablegroup.chat.cache.TableGroupChatUnreadHelper;
import com.berkayb.soundconnect.modules.tablegroup.chat.entity.TableGroupMessage;
import com.berkayb.soundconnect.modules.tablegroup.chat.enums.MessageType;
import com.berkayb.soundconnect.modules.tablegroup.chat.mapper.TableGroupMessageMapper;
import com.berkayb.soundconnect.modules.tablegroup.chat.repository.TableGroupMessageRepository;
import com.berkayb.soundconnect.modules.tablegroup.entity.*;
import com.berkayb.soundconnect.modules.tablegroup.enums.*;
import com.berkayb.soundconnect.modules.tablegroup.game.entity.*;
import com.berkayb.soundconnect.modules.tablegroup.game.enums.*;
import com.berkayb.soundconnect.modules.tablegroup.game.random.TableGroupDiceRoller;
import com.berkayb.soundconnect.modules.tablegroup.game.realtime.TableGroupGameRealtimePublisher;
import com.berkayb.soundconnect.modules.tablegroup.game.repository.*;
import com.berkayb.soundconnect.modules.tablegroup.game.service.*;
import com.berkayb.soundconnect.modules.tablegroup.game.support.TableGroupGameTimeProvider;
import com.berkayb.soundconnect.modules.tablegroup.mapper.TableGroupMapper;
import com.berkayb.soundconnect.modules.tablegroup.notification.outbox.TableGroupNotificationOutboxService;
import com.berkayb.soundconnect.modules.tablegroup.observability.TableGroupMetrics;
import com.berkayb.soundconnect.modules.tablegroup.repository.TableGroupRepository;
import com.berkayb.soundconnect.modules.tablegroup.support.TableGroupEntityFinder;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.modules.venue.repository.VenueRepository;
import com.berkayb.soundconnect.shared.config.JpaAuditingConfig;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DataJpaTest
@Import(JpaAuditingConfig.class)
@Testcontainers(disabledWithoutDocker = true)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TableGroupCancellationGamePurgeJpaTest {
	@Container
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
	}

	@Autowired private TableGroupRepository tableGroupRepository;
	@Autowired private CityRepository cityRepository;
	@Autowired private TableGroupMessageRepository messageRepository;
	@Autowired private TableGroupGameRepository gameRepository;
	@Autowired private TableGroupGamePlayerRepository playerRepository;
	@Autowired private TableGroupGameActionRepository actionRepository;
	@Autowired private UserRepository userRepository;
	@Autowired private EntityManager entityManager;
	@Autowired private PlatformTransactionManager transactionManager;

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	void cancellationCommitsAfterBulkPurgeWithLazyRecipients(boolean activeGame) {
		Instant now = Instant.now();
		UUID ownerId = UUID.randomUUID();
		UUID recipientId = UUID.randomUUID();
		TableGroupNotificationOutboxService outbox = mock(TableGroupNotificationOutboxService.class);
		TableGroupChatUnreadHelper unread = mock(TableGroupChatUnreadHelper.class);
		TableGroupMetrics metrics = mock(TableGroupMetrics.class);
		TableGroupGameTimeProvider timeProvider = mock(TableGroupGameTimeProvider.class);
		when(timeProvider.now()).thenReturn(now);
		TableGroupEntityFinder finder = new TableGroupEntityFinder(tableGroupRepository, messageRepository);
		TableGroupGameServiceImpl gameService = new TableGroupGameServiceImpl(
				finder,
				tableGroupRepository,
				gameRepository,
				playerRepository,
				actionRepository,
				messageRepository,
				mock(TableGroupMessageMapper.class),
				userRepository,
				mock(TableGroupGameProjectionService.class),
				mock(TableGroupGameRealtimePublisher.class),
				timeProvider,
				mock(TableGroupDiceRoller.class),
				mock(TableGroupRateLimitGuard.class),
				metrics
		);
		TableGroupGameLifecycleService lifecycle = new TableGroupGameLifecycleService(gameService);
		TableGroupServiceImpl tableService = new TableGroupServiceImpl(
				outbox,
				tableGroupRepository,
				mock(TableGroupMapper.class),
				cityRepository,
				mock(DistrictRepository.class),
				mock(NeighborhoodRepository.class),
				finder,
				messageRepository,
				unread,
				userRepository,
				mock(PersonalProfileAvatarBatchResolver.class),
				mock(StudioProfileRepository.class),
				mock(VenueRepository.class),
				mock(MediaAssetService.class),
				mock(TableGroupRateLimitGuard.class),
				metrics,
				lifecycle,
				mock(com.berkayb.soundconnect.modules.tablegroup.scheduler.TableGroupExpiryWorker.class)
		);
		TransactionTemplate transaction = new TransactionTemplate(transactionManager);

		Seed seed = transaction.execute(status -> seed(
				now, ownerId, recipientId, activeGame));
		assertThat(seed).isNotNull();

		transaction.executeWithoutResult(status ->
				tableService.cancelTableGroup(ownerId, seed.tableGroupId()));

		transaction.executeWithoutResult(status -> {
			TableGroup cancelled = tableGroupRepository.findById(seed.tableGroupId()).orElseThrow();
			assertThat(cancelled.getStatus()).isEqualTo(TableGroupStatus.CANCELLED);
			assertThat(gameRepository.findIdsByTableGroupId(seed.tableGroupId())).isEmpty();
			assertThat(playerRepository.findByGameIdOrderByJoinedAtAscIdAsc(seed.gameId())).isEmpty();
			assertThat(messageRepository.countByTableGroupIdAndDeletedAtIsNull(seed.tableGroupId()))
					.isZero();
		});
		verify(outbox).enqueue(
				eq(recipientId),
				eq(NotificationType.TABLE_CANCELLED),
				anyString(),
				anyString(),
				anyMap()
		);
	}

	private Seed seed(
			Instant now,
			UUID ownerId,
			UUID recipientId,
			boolean activeGame
	) {
		City city = cityRepository.save(City.builder()
				.name("Cancellation City " + UUID.randomUUID())
				.build());
		TableGroup tableGroup = tableGroupRepository.saveAndFlush(TableGroup.builder()
				.ownerId(ownerId)
				.createRequestKey(UUID.randomUUID())
				.venueName("Cancellation Venue")
				.description("Cancellation table")
				.maxPersonCount(2)
				.genderPrefs(List.of("MALE", "FEMALE"))
				.ageMin(20)
				.ageMax(40)
				.startAt(now.minusSeconds(60))
				.meetingAt(now.plusSeconds(1800))
				.expiresAt(now.plusSeconds(3600))
				.status(TableGroupStatus.ACTIVE)
				.city(city)
				.participants(new HashSet<>(List.of(
						participant(ownerId, now),
						participant(recipientId, now)
				)))
				.build());
		TableGroupGame game = TableGroupGame.builder()
				.version(0)
				.revision(1)
				.tableGroupId(tableGroup.getId())
				.createdBy(ownerId)
				.createdByUsername("owner")
				.createRequestId(UUID.randomUUID())
				.topic(TableGroupGameTopic.WHO_PAYS)
				.mode(TableGroupGameMode.DICE)
				.status(activeGame ? TableGroupGameStatus.IN_PROGRESS : TableGroupGameStatus.COMPLETED)
				.phase(activeGame ? TableGroupGamePhase.DICE : TableGroupGamePhase.COMPLETED)
				.roundNumber(1)
				.actionDeadlineAt(activeGame ? now.plusSeconds(20) : null)
				.completedAt(activeGame ? null : now.minusSeconds(1))
				.selectedUserId(activeGame ? null : ownerId)
				.selectedUsername(activeGame ? null : "owner")
				.outcome(activeGame ? null : TableGroupGameOutcome.ASSIGNED)
				.resultMessage(activeGame ? null : "result")
				.build();
		game = gameRepository.saveAndFlush(game);
		playerRepository.saveAndFlush(TableGroupGamePlayer.builder()
				.gameId(game.getId())
				.userId(ownerId)
				.username("owner")
				.status(TableGroupGamePlayerStatus.ACTIVE)
				.joinedAt(now)
				.build());
		messageRepository.saveAndFlush(TableGroupMessage.builder()
				.createdAt(LocalDateTime.ofInstant(now, ZoneOffset.UTC))
				.tableGroupId(tableGroup.getId())
				.senderId(ownerId)
				.gameId(game.getId())
				.content("game")
				.messageType(MessageType.GAME)
				.build());
		entityManager.clear();
		return new Seed(tableGroup.getId(), game.getId());
	}

	private TableGroupParticipant participant(UUID userId, Instant now) {
		return TableGroupParticipant.builder()
				.userId(userId)
				.status(ParticipantStatus.ACCEPTED)
				.joinedAt(now)
				.build();
	}

	private record Seed(UUID tableGroupId, UUID gameId) {
	}
}
