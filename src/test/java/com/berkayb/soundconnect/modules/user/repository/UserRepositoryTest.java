package com.berkayb.soundconnect.modules.user.repository;

import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.AuthProvider;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.role.enums.RoleEnum;
import com.berkayb.soundconnect.shared.util.UsernameUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
		// JPA/DB
		"spring.jpa.hibernate.ddl-auto=update",
		"spring.jpa.properties.hibernate.jdbc.lob.non_contextual_creation=true",
		"spring.flyway.enabled=false",
		"spring.liquibase.enabled=false",
		// bazı modüllerin beklediği dummy secretlar
		"SOUNDCONNECT_JWT_SECRETKEY=test-jwt-secret-key-at-least-32-bytes-long",
		"app.jwt.secret=test-jwt-secret-key-at-least-32-bytes-long"
})
@Import(UserRepositoryTest.AuditingTestConfig.class) // << mini auditing config
@Tag("repo")
class UserRepositoryTest {
	
	@Container
	static final PostgreSQLContainer<?> POSTGRES =
			new PostgreSQLContainer<>("postgres:16-alpine")
					.withDatabaseName("soundconnect_test")
					.withUsername("sc")
					.withPassword("sc");
	
	@DynamicPropertySource
	static void props(DynamicPropertyRegistry r) {
		r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		r.add("spring.datasource.username", POSTGRES::getUsername);
		r.add("spring.datasource.password", POSTGRES::getPassword);
	}
	
	@TestConfiguration
	@EnableJpaAuditing
	static class AuditingTestConfig {
		@Bean
		AuditorAware<UUID> auditorAware() {
			// auditing aktif kalsın ama aktör boş olsun (createdBy vs. yoksa sorun olmaz)
			return () -> Optional.empty();
		}
	}
	
	@Autowired
	UserRepository userRepository;

	@Autowired
	TestEntityManager entityManager;
	
	private User newUser(String username, String email) {
		return User.builder()
		           .username(username)
		           .password("secret")
		           .email(email)
		           .build();
	}

	private String randomUsername(String prefix) {
		return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
	}
	
	@Test @DisplayName("findByUsername → kullanıcı bulundu")
	void findByUsername_found() {
		String supplied = "\u00A0UsEr_" + randomUsername("") + "\u2003";
		var u = userRepository.saveAndFlush(newUser(supplied, "a@test.com"));
		assertThat(u.getUsername()).isEqualTo(UsernameUtils.normalize(supplied));
		assertThat(userRepository.findByUsername(u.getUsername().toUpperCase(java.util.Locale.ROOT)))
				.isEmpty();
		var found = userRepository.findByUsername(UsernameUtils.normalize(supplied));
		assertThat(found).isPresent();
		assertThat(found.get().getId()).isEqualTo(u.getId());
		assertThat(userRepository.existsByUsernameAndIdNot(u.getUsername(), u.getId())).isFalse();
	}
	
	@Test @DisplayName("findByUsername → kullanıcı yok")
	void findByUsername_notFound() {
		var found = userRepository.findByUsername(randomUsername("nope-"));
		assertThat(found).isEmpty();
	}
	
	@Test @DisplayName("existsByUsername → true/false")
	void existsByUsername_works() {
		var name = randomUsername("exists_");
		userRepository.save(newUser(name, "x@test.com"));
		assertThat(userRepository.existsByUsername(UsernameUtils.normalize(name.toUpperCase(java.util.Locale.ROOT)))).isTrue();
		assertThat(userRepository.existsByUsername(randomUsername("other_"))).isFalse();
	}
	
	@Test @DisplayName("existsByEmail → true/false")
	void existsByEmail_works() {
		var email = "e" + UUID.randomUUID() + "@test.com";
		userRepository.save(newUser(randomUsername("u_"), email));
		assertThat(userRepository.existsByEmail(email)).isTrue();
		assertThat(userRepository.existsByEmail("none@test.com")).isFalse();
	}

	@Test
	void findRoleNamesByUserIdReturnsScalarAuthorizationProjection() {
		Role musician = entityManager.persistAndFlush(
				Role.builder().name(RoleEnum.ROLE_MUSICIAN.name()).build());
		Role venue = entityManager.persistAndFlush(
				Role.builder().name(RoleEnum.ROLE_VENUE.name()).build());
		User user = newUser(randomUsername("roles_"), "roles@test.com");
		user.setRoles(Set.of(musician, venue));
		User saved = userRepository.saveAndFlush(user);
		entityManager.clear();

		assertThat(userRepository.findRoleNamesByUserId(saved.getId()))
				.containsExactlyInAnyOrder(
						RoleEnum.ROLE_MUSICIAN.name(), RoleEnum.ROLE_VENUE.name());
		assertThat(userRepository.findRoleNamesByUserId(UUID.randomUUID())).isEmpty();
	}
	
	@Test @DisplayName("findByEmail → bulundu/bulunamadı")
	void findByEmail_works() {
		var email = "f" + UUID.randomUUID() + "@test.com";
		userRepository.save(newUser(randomUsername("u_"), email));
		assertThat(userRepository.findByEmail(email)).isPresent();
		assertThat(userRepository.findByEmail("missing@test.com")).isEmpty();
	}
	
	@Test @DisplayName("findByEmailVerificationToken → bulundu/bulunamadı")
	void findByEmailVerificationToken_works() {
		var u = newUser(randomUsername("u_"), "tok@test.com");
		u.setEmailVerificationToken("tok-" + UUID.randomUUID());
		userRepository.save(u);
		
		assertThat(userRepository.findByEmailVerificationToken(u.getEmailVerificationToken())).isPresent();
		assertThat(userRepository.findByEmailVerificationToken("nope-" + UUID.randomUUID())).isEmpty();
	}
	
	@Test @DisplayName("duplicate username → unique constraint ile patlamalı")
	void save_shouldFail_onDuplicateUsername() {
		var username = randomUsername("dup_");
		userRepository.save(newUser(username, "a@test.com"));
		
		var duplicate = newUser(" " + username.toUpperCase(java.util.Locale.ROOT) + " ", "b@test.com");
		assertThatThrownBy(() -> userRepository.saveAndFlush(duplicate))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void googleProviderSubjectIsQueryableAndUniqueWithinProvider() {
		String subject = "google-" + UUID.randomUUID();
		User first = newUser(randomUsername("g_"), "google-a@test.com");
		first.setProvider(AuthProvider.GOOGLE);
		first.setProviderSubject(subject);
		userRepository.saveAndFlush(first);

		assertThat(userRepository.findByProviderAndProviderSubject(AuthProvider.GOOGLE, subject))
				.contains(first);

		User duplicate = newUser(randomUsername("g_"), "google-b@test.com");
		duplicate.setProvider(AuthProvider.GOOGLE);
		duplicate.setProviderSubject(subject);
		assertThatThrownBy(() -> userRepository.saveAndFlush(duplicate))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void usernameChangedAtRoundTripsAsNullablePersistentUtcWallClock() {
		LocalDateTime changedAt = LocalDateTime.of(2026, 7, 24, 12, 34, 56);
		User user = newUser(randomUsername("cooldown_"), "cooldown@test.com");
		user.setUsernameChangedAt(changedAt);

		User saved = userRepository.saveAndFlush(user);
		entityManager.clear();

		assertThat(userRepository.findById(saved.getId()))
				.get()
				.extracting(User::getUsernameChangedAt)
				.isEqualTo(changedAt);
	}
}
