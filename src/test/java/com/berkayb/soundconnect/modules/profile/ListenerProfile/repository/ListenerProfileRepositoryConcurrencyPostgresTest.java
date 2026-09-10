package com.berkayb.soundconnect.modules.profile.ListenerProfile.repository;

import com.berkayb.soundconnect.modules.profile.ListenerProfile.entity.ListenerProfile;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.repository.MusicianProfileRepository;
import com.berkayb.soundconnect.modules.profile.shared.type.PersonalProfileTypePolicy;
import com.berkayb.soundconnect.modules.role.enums.RoleEnum;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * PostgreSQL contract coverage for listener visibility locks and the
 * one-personal-profile invariant. H2 cannot validate either PostgreSQL's
 * outer-join locking restrictions or real row-lock serialization.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PersonalProfileTypePolicy.class)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ListenerProfileRepositoryConcurrencyPostgresTest {

	@Container
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
			.withDatabaseName("soundconnect_listener_visibility")
			.withUsername("soundconnect")
			.withPassword("soundconnect");

	@DynamicPropertySource
	static void databaseProperties(DynamicPropertyRegistry properties) {
		properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		properties.add("spring.datasource.username", POSTGRES::getUsername);
		properties.add("spring.datasource.password", POSTGRES::getPassword);
		properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
		properties.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
		properties.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
		properties.add(
				"spring.jpa.properties.hibernate.dialect",
				() -> "org.hibernate.dialect.PostgreSQLDialect");
	}

	@Autowired ListenerProfileRepository listenerProfileRepository;
	@Autowired MusicianProfileRepository musicianProfileRepository;
	@Autowired UserRepository userRepository;
	@Autowired PersonalProfileTypePolicy personalProfileTypePolicy;
	@Autowired PlatformTransactionManager transactionManager;

	@Test
	void contentGuardReadsCommittedVisibilityInsteadOfTheStaleManagedProfile() throws Exception {
		User owner = userRepository.saveAndFlush(user("stale_guard"));
		ListenerProfile stored = listenerProfileRepository.saveAndFlush(ListenerProfile.builder().user(owner)
				.visibilityMode(ListenerVisibilityMode.STANDARD).visibilityChoiceCompleted(true).build());
		try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
			new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
				ListenerProfile stale = listenerProfileRepository.findById(stored.getId()).orElseThrow();
				try {
					executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(other -> {
						var fresh = listenerProfileRepository.findByIdForUpdate(stored.getId()).orElseThrow();
						fresh.setVisibilityMode(ListenerVisibilityMode.GHOST);
						listenerProfileRepository.saveAndFlush(fresh);
					})).get(10, TimeUnit.SECONDS);
				} catch (Exception failure) { throw new IllegalStateException(failure); }
				assertThat(stale.getVisibilityMode()).isEqualTo(ListenerVisibilityMode.STANDARD);
				var current = listenerProfileRepository.lockContentVisibility(stored.getId()).orElseThrow();
				assertThat(current.getMode()).isEqualTo("GHOST");
				assertThat(current.getChoiceCompleted()).isTrue();
			});
		}
	}

	@Test
	void publicShellIdentityAndSearchHideInactiveAndUnverifiedOwners() {
		User owner = userRepository.saveAndFlush(user("disabled_visibility"));
		ListenerProfile profile = listenerProfileRepository.saveAndFlush(ListenerProfile.builder().user(owner)
				.visibilityMode(ListenerVisibilityMode.STANDARD).visibilityChoiceCompleted(true).build());
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			assertThat(listenerProfileRepository.findForPublicById(profile.getId())).isPresent();
		});
		owner.setStatus(UserStatus.INACTIVE); userRepository.saveAndFlush(owner);
		assertHidden(owner, profile);
		owner.setStatus(UserStatus.ACTIVE); owner.setEmailVerified(false); userRepository.saveAndFlush(owner);
		assertHidden(owner, profile);
	}

	private void assertHidden(User owner, ListenerProfile profile) {
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			assertThat(listenerProfileRepository.findForPublicById(profile.getId())).isEmpty();
			assertThat(listenerProfileRepository.findForPublicIdentityByUserId(owner.getId())).isEmpty();
			assertThat(listenerProfileRepository.searchForPublicDiscovery(owner.getUsername(), owner.getUsername(),
					ListenerVisibilityMode.GHOST, PageRequest.of(0, 10))).isEmpty();
		});
	}

	@Test
	void visibilityReadQueriesExecuteWithTheirUserFetchesOnPostgres() {
		User ghostUser = userRepository.saveAndFlush(user("visibility_ghost"));
		User standardUser = userRepository.saveAndFlush(user("visibility_standard"));
		ListenerProfile ghost = listenerProfileRepository.saveAndFlush(ListenerProfile.builder()
				.user(ghostUser)
				.name("Hidden name")
				.description("Hidden biography")
				.visibilityMode(ListenerVisibilityMode.GHOST)
				.visibilityChoiceCompleted(true)
				.build());
		ListenerProfile standard = listenerProfileRepository.saveAndFlush(ListenerProfile.builder()
				.user(standardUser)
				.name("Visible name")
				.description("Visible biography")
				.visibilityMode(ListenerVisibilityMode.STANDARD)
				.visibilityChoiceCompleted(true)
				.build());

		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			ListenerProfile byPublicIdentity = listenerProfileRepository
					.findForPublicIdentityByUserId(ghostUser.getId())
					.orElseThrow();
			assertThat(byPublicIdentity.getUser().getUsername()).isEqualTo(ghostUser.getUsername());

			ListenerProfile byUser = listenerProfileRepository
					.findByUserIdForVisibilityRead(standardUser.getId())
					.orElseThrow();
			assertThat(byUser.getUser().getUsername()).isEqualTo(standardUser.getUsername());

			ListenerProfile byProfile = listenerProfileRepository
					.findByIdForVisibilityRead(standard.getId())
					.orElseThrow();
			assertThat(byProfile.getUser().getUsername()).isEqualTo(standardUser.getUsername());

			assertThat(listenerProfileRepository.findAllByUserIdInForVisibilityRead(
					List.of(ghostUser.getId(), standardUser.getId())))
					.extracting(ListenerProfile::getId)
					.containsExactlyInAnyOrder(ghost.getId(), standard.getId());

			assertThat(listenerProfileRepository.searchForPublicDiscovery(
					ghostUser.getUsername(),
					ghostUser.getUsername(),
					ListenerVisibilityMode.GHOST,
					PageRequest.of(0, 10)))
					.singleElement()
					.satisfies(found -> {
						assertThat(found.getId()).isEqualTo(ghost.getId());
						assertThat(found.getUser().getUsername()).isEqualTo(ghostUser.getUsername());
					});
		});
	}

	@Test
	void publicIdentitySharedLockBlocksVisibilityWriterUntilReaderCommits() throws Exception {
		User user = userRepository.saveAndFlush(user("visibility_lock"));
		ListenerProfile profile = listenerProfileRepository.saveAndFlush(ListenerProfile.builder()
				.user(user)
				.visibilityMode(ListenerVisibilityMode.STANDARD)
				.visibilityChoiceCompleted(true)
				.build());

		CountDownLatch readerHasLock = new CountDownLatch(1);
		CountDownLatch releaseReader = new CountDownLatch(1);
		CountDownLatch writerIsAttemptingLock = new CountDownLatch(1);

		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			Future<?> reader = executor.submit(() ->
					new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
						ListenerProfile locked = listenerProfileRepository
								.findForPublicIdentityByUserId(user.getId())
								.orElseThrow();
						assertThat(locked.getVisibilityMode()).isEqualTo(ListenerVisibilityMode.STANDARD);
						readerHasLock.countDown();
						await(releaseReader, "Reader release timeout");
					}));

			assertThat(readerHasLock.await(10, TimeUnit.SECONDS)).isTrue();
			Future<?> writer = executor.submit(() ->
					new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
						writerIsAttemptingLock.countDown();
						ListenerProfile locked = listenerProfileRepository
								.findByUserIdForUpdate(user.getId())
								.orElseThrow();
						locked.setVisibilityMode(ListenerVisibilityMode.GHOST);
						listenerProfileRepository.saveAndFlush(locked);
					}));

			try {
				assertThat(writerIsAttemptingLock.await(10, TimeUnit.SECONDS)).isTrue();
				assertThatExceptionOfType(TimeoutException.class)
						.isThrownBy(() -> writer.get(750, TimeUnit.MILLISECONDS));
			} finally {
				releaseReader.countDown();
			}

			reader.get(10, TimeUnit.SECONDS);
			writer.get(10, TimeUnit.SECONDS);
		}

		assertThat(listenerProfileRepository.findById(profile.getId()))
				.get()
				.extracting(ListenerProfile::getVisibilityMode)
				.isEqualTo(ListenerVisibilityMode.GHOST);
	}

	@Test
	void listenerAndMusicianAcquisitionRaceCreatesExactlyOnePersonalProfile() throws Exception {
		User user = userRepository.saveAndFlush(user("personal_profile_race"));
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);

		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			Future<AcquisitionOutcome> listener = executor.submit(() -> acquirePersonalProfile(
					ready, start, user.getId(), RoleEnum.ROLE_LISTENER));
			Future<AcquisitionOutcome> musician = executor.submit(() -> acquirePersonalProfile(
					ready, start, user.getId(), RoleEnum.ROLE_MUSICIAN));

			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			start.countDown();

			assertThat(List.of(
					listener.get(15, TimeUnit.SECONDS),
					musician.get(15, TimeUnit.SECONDS)))
					.containsExactlyInAnyOrder(AcquisitionOutcome.CREATED, AcquisitionOutcome.REJECTED_IMMUTABLE);
		}

		assertThat(List.of(
				listenerProfileRepository.findByUserId(user.getId()).isPresent(),
				musicianProfileRepository.findByUserId(user.getId()).isPresent()))
				.containsExactlyInAnyOrder(true, false);
	}

	private AcquisitionOutcome acquirePersonalProfile(
			CountDownLatch ready,
			CountDownLatch start,
			UUID userId,
			RoleEnum targetRole
	) throws Exception {
		ready.countDown();
		start.await();
		try {
			new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
				User lockedUser = personalProfileTypePolicy.lockAndAssertCanAcquire(userId, targetRole);
				if (targetRole == RoleEnum.ROLE_LISTENER) {
					listenerProfileRepository.saveAndFlush(ListenerProfile.builder()
							.user(lockedUser)
							.visibilityMode(ListenerVisibilityMode.STANDARD)
							.build());
				} else {
					musicianProfileRepository.saveAndFlush(MusicianProfile.builder()
							.user(lockedUser)
							.stageName("Race musician")
							.build());
				}
			});
			return AcquisitionOutcome.CREATED;
		} catch (SoundConnectException exception) {
			if (exception.getErrorType() != ErrorType.PROFILE_TYPE_IMMUTABLE) throw exception;
			return AcquisitionOutcome.REJECTED_IMMUTABLE;
		}
	}

	private void await(CountDownLatch latch, String timeoutMessage) {
		try {
			if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException(timeoutMessage);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while testing listener profile locking", exception);
		}
	}

	private User user(String prefix) {
		String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
		return User.builder()
				.username(prefix + "_" + suffix)
				.password("encoded-password")
				.email(prefix + "_" + suffix + "@soundconnect.test")
				.status(UserStatus.ACTIVE)
				.emailVerified(true)
				.build();
	}

	private enum AcquisitionOutcome {
		CREATED,
		REJECTED_IMMUTABLE
	}
}
