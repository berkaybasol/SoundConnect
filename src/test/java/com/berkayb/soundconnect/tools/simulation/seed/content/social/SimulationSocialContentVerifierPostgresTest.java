package com.berkayb.soundconnect.tools.simulation.seed.content.social;

import com.berkayb.soundconnect.modules.event.audience.EventAudienceService;
import com.berkayb.soundconnect.modules.overthinking.profileshare.OverthinkingProfileShareRepository;
import com.berkayb.soundconnect.modules.overthinking.repository.OverthinkingPostRepository;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.entity.ListenerProfile;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.repository.ListenerProfileRepository;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.support.ListenerVisibilityPolicy;
import com.berkayb.soundconnect.modules.tablegroup.dto.request.TableGroupCreateRequestDto;
import com.berkayb.soundconnect.modules.tablegroup.dto.response.TableGroupParticipantDto;
import com.berkayb.soundconnect.modules.tablegroup.dto.response.TableGroupResponseDto;
import com.berkayb.soundconnect.modules.tablegroup.enums.ParticipantStatus;
import com.berkayb.soundconnect.modules.tablegroup.enums.TableGroupStatus;
import com.berkayb.soundconnect.modules.tablegroup.profileshare.TableGroupProfileShareRepository;
import com.berkayb.soundconnect.modules.tablegroup.service.TableGroupService;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.when;

/** Exercises the verifier transaction boundary against PostgreSQL's real shared-lock semantics. */
@DataJpaTest(properties = {
		"spring.config.location=classpath:/application-test.yml",
		"spring.config.import=",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect"
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
		SimulationSocialContentVerifierPostgresTest.VerifierConfiguration.class,
		ListenerVisibilityPolicy.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SimulationSocialContentVerifierPostgresTest {

	@Container
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
			.withDatabaseName("simulation_social_verifier")
			.withUsername("simulation_social_verifier")
			.withPassword("simulation_social_verifier")
			.withReuse(false);

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
		registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
	}

	@Autowired private PlatformTransactionManager transactionManager;
	@Autowired private UserRepository userRepository;
	@Autowired private ListenerProfileRepository listenerProfileRepository;
	@Autowired private ListenerVisibilityPolicy listenerVisibilityPolicy;
	@Autowired private SimulationSocialContentVerifier verifier;

	@MockitoBean private TableGroupService tableGroups;
	@MockitoBean private EventAudienceService eventAudience;

	private UUID ownerId;

	@BeforeEach
	void persistListener() {
		TransactionTemplate transaction = new TransactionTemplate(transactionManager);
		ownerId = transaction.execute(status -> {
			String identity = UUID.randomUUID().toString().replace("-", "");
			User owner = userRepository.saveAndFlush(User.builder()
					.username("listener" + identity.substring(0, 12))
					.email(identity + "@example.test")
					.password("unused-test-password")
					.status(UserStatus.ACTIVE)
					.emailVerified(true)
					.build());
			listenerProfileRepository.saveAndFlush(ListenerProfile.builder()
					.user(owner)
					.name("Simulation listener")
					.visibilityChoiceCompleted(true)
					.build());
			return owner.getId();
		});
	}

	@Test
	void tableGroupVerificationAllowsTheListenerSharedLockInItsOwnTransaction() {
		UUID tableId = UUID.randomUUID();
		UUID cityId = UUID.randomUUID();
		UUID districtId = UUID.randomUUID();
		UUID neighborhoodId = UUID.randomUUID();
		Instant startAt = Instant.parse("2026-09-11T10:00:00Z");
		Instant meetingAt = startAt.plus(Duration.ofHours(4));
		SimulationSocialContentCheckpoint.TableGroupAggregate item =
				new SimulationSocialContentCheckpoint.TableGroupAggregate(
						"table-group-01", "listener-owner", ownerId, tableId, meetingAt);
		TableGroupCreateRequestDto expected = new TableGroupCreateRequestDto(
				null, "Plak Arası", "Bir açıklama", 6,
				List.of("FEMALE", "MALE", "OTHER"), 21, 45, meetingAt,
				cityId, districtId, neighborhoodId);
		Map<UUID, String> expectedParticipants = new LinkedHashMap<>();
		expectedParticipants.put(ownerId, null);

		when(tableGroups.getTableGroupDetail(ownerId, tableId)).thenAnswer(invocation -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
			var restrictions = listenerVisibilityPolicy.publicVisibilityRestrictions(Set.of(ownerId));
			assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
			assertThat(restrictions.ghostUserIds()).isEmpty();
			return table(tableId, cityId, districtId, neighborhoodId, startAt, meetingAt);
		});

		assertThat(AopUtils.isAopProxy(verifier)).isTrue();
		assertThatCode(() -> verifier.verifyTableGroup(item, expected, expectedParticipants))
				.doesNotThrowAnyException();
	}

	private TableGroupResponseDto table(
			UUID tableId,
			UUID cityId,
			UUID districtId,
			UUID neighborhoodId,
			Instant startAt,
			Instant meetingAt
	) {
		return new TableGroupResponseDto(
				tableId,
				ownerId,
				"listener-owner",
				null,
				null,
				"Plak Arası",
				"Bir açıklama",
				6,
				List.of("OTHER", "FEMALE", "MALE"),
				21,
				45,
				startAt,
				meetingAt,
				startAt.plus(Duration.ofHours(24)),
				TableGroupStatus.INACTIVE,
				Set.of(new TableGroupParticipantDto(
						ownerId,
						startAt.plusSeconds(30),
						ParticipantStatus.ACCEPTED,
						null,
						"listener-owner",
						null)),
				new TableGroupResponseDto.LocationDto(cityId, "İstanbul"),
				new TableGroupResponseDto.LocationDto(districtId, "Kadıköy"),
				new TableGroupResponseDto.LocationDto(neighborhoodId, "Göztepe")
		);
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class VerifierConfiguration {

		@Bean
		SimulationSocialContentVerifier simulationSocialContentVerifier(
				OverthinkingPostRepository overthinkingPosts,
				OverthinkingProfileShareRepository overthinkingShares,
				TableGroupService tableGroups,
				TableGroupProfileShareRepository tableGroupShares,
				EventAudienceService eventAudience
		) {
			return new SimulationSocialContentVerifier(
					overthinkingPosts, overthinkingShares, tableGroups, tableGroupShares, eventAudience);
		}
	}
}
