package com.berkayb.soundconnect.modules.user.repository;

import com.berkayb.soundconnect.auth.dto.request.GoogleCompleteProfileRequestDto;
import com.berkayb.soundconnect.auth.model.VerifiedGoogleIdentity;
import com.berkayb.soundconnect.auth.security.JwtTokenProvider;
import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.auth.service.GoogleAccountService;
import com.berkayb.soundconnect.auth.service.GoogleBusinessApplications;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.support.ListenerProfileChoiceStatusReader;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.repository.MusicianProfileRepository;
import com.berkayb.soundconnect.modules.profile.shared.factory.ProfileFactory;
import com.berkayb.soundconnect.modules.profile.shared.type.PersonalProfileTypePolicy;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.role.enums.RoleEnum;
import com.berkayb.soundconnect.modules.role.repository.RoleRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.AuthProvider;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create", showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(UserRepositoryTest.AuditingTestConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class GoogleConfirmationPostgresTest {
	@Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4-alpine");

	@DynamicPropertySource static void isolatedDatabase(DynamicPropertyRegistry r) {
		r.add("spring.datasource.url", postgres::getJdbcUrl);
		r.add("spring.datasource.username", postgres::getUsername);
		r.add("spring.datasource.password", postgres::getPassword);
	}

	@Autowired UserRepository users;
	@Autowired RoleRepository roles;
	@Autowired MusicianProfileRepository musicians;
	@Autowired PlatformTransactionManager transactionManager;

	@Test void concurrentConfirmationCreatesOneAccountAndLegacyRevisionSurvivesReload() throws Exception {
		var tx = new TransactionTemplate(transactionManager);
		var password = mock(PasswordEncoder.class);
		when(password.encode(any())).thenReturn("fixture-hash");
		var profiles = mock(ProfileFactory.class);
		var jwt = mock(JwtTokenProvider.class);
		when(jwt.generateToken(any())).thenAnswer(call -> {
			UserDetailsImpl principal = call.getArgument(0);
			return "version-" + principal.getUser().getSessionVersion();
		});
		var service = new GoogleAccountService(users, roles, new PersonalProfileTypePolicy(users), profiles, password,
				jwt, mock(ListenerProfileChoiceStatusReader.class), mock(GoogleBusinessApplications.class));
		tx.executeWithoutResult(s -> roles.saveAndFlush(Role.builder().name("ROLE_MUSICIAN").build()));
		var form = new GoogleCompleteProfileRequestDto("unit-proof","chosen-name", RoleEnum.ROLE_MUSICIAN, true);
		var identity = new VerifiedGoogleIdentity("concurrent-id", "same@example.invalid", null, true);
		var ready = new CountDownLatch(2);
		Callable<UUID> complete = () -> {
			ready.countDown();
			if (!ready.await(10, TimeUnit.SECONDS)) throw new AssertionError("Both confirmations must be ready");
			return tx.execute(s -> service.complete(identity, form)).userId();
		};
		try (var workers = Executors.newFixedThreadPool(2)) {
			var first = workers.submit(complete);
			var second = workers.submit(complete);
			assertThat(first.get(20, TimeUnit.SECONDS)).isEqualTo(second.get(20, TimeUnit.SECONDS));
		}
		assertThat(users.count()).isOne();
		verify(profiles).createProfileIfNeeded(any(), eq(RoleEnum.ROLE_MUSICIAN));

		var legacy = tx.execute(s -> users.saveAndFlush(User.builder().username("legacy").password("opaque")
				.email("legacy@example.invalid").provider(AuthProvider.GOOGLE).status(UserStatus.ACTIVE)
				.emailVerified(true).build()));
		var legacyIdentity = new VerifiedGoogleIdentity("legacy-subject", legacy.getEmail(), null, true);
		var retained = tx.execute(s -> musicians.saveAndFlush(MusicianProfile.builder()
				.user(users.getReferenceById(legacy.getId())).build()));
		var legacyForm = new GoogleCompleteProfileRequestDto("unit-proof", "chosen-legacy", RoleEnum.ROLE_MUSICIAN, true);
		var session = tx.execute(s -> service.complete(legacyIdentity, legacyForm));
		assertThat(session.userId()).isEqualTo(legacy.getId());
		assertThat(session.token()).isEqualTo("version-1");
		assertThat(users.findById(legacy.getId()).orElseThrow().getSessionVersion()).isOne();
		assertThat(musicians.findById(retained.getId())).isPresent();
		verify(profiles, times(1)).createProfileIfNeeded(any(), eq(RoleEnum.ROLE_MUSICIAN));
	}
}
