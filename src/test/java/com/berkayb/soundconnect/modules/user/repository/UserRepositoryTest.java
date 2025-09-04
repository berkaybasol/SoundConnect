package com.berkayb.soundconnect.modules.user.repository;

import com.berkayb.soundconnect.modules.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
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

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
		// JPA/DB
		"spring.jpa.hibernate.ddl-auto=update",
		"spring.jpa.properties.hibernate.jdbc.lob.non_contextual_creation=true",
		"spring.flyway.enabled=false",
		"spring.liquibase.enabled=false",
		// bazı modüllerin beklediği dummy secretlar
		"SOUNDCONNECT_JWT_SECRETKEY=dummy",
		"app.jwt.secret=dummy"
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
	
	private User newUser(String username, String email) {
		return User.builder()
		           .username(username)
		           .password("secret")
		           .email(email)
		           .build();
	}
	
	@Test @DisplayName("findByUsername → kullanıcı bulundu")
	void findByUsername_found() {
		var u = userRepository.save(newUser("user_" + UUID.randomUUID(), "a@test.com"));
		var found = userRepository.findByUsername(u.getUsername());
		assertThat(found).isPresent();
		assertThat(found.get().getId()).isEqualTo(u.getId());
	}
	
	@Test @DisplayName("findByUsername → kullanıcı yok")
	void findByUsername_notFound() {
		var found = userRepository.findByUsername("nope-" + UUID.randomUUID());
		assertThat(found).isEmpty();
	}
	
	@Test @DisplayName("existsByUsername → true/false")
	void existsByUsername_works() {
		var name = "exists_" + UUID.randomUUID();
		userRepository.save(newUser(name, "x@test.com"));
		assertThat(userRepository.existsByUsername(name)).isTrue();
		assertThat(userRepository.existsByUsername("other_" + UUID.randomUUID())).isFalse();
	}
	
	@Test @DisplayName("existsByEmail → true/false")
	void existsByEmail_works() {
		var email = "e" + UUID.randomUUID() + "@test.com";
		userRepository.save(newUser("u_" + UUID.randomUUID(), email));
		assertThat(userRepository.existsByEmail(email)).isTrue();
		assertThat(userRepository.existsByEmail("none@test.com")).isFalse();
	}
	
	@Test @DisplayName("findByEmail → bulundu/bulunamadı")
	void findByEmail_works() {
		var email = "f" + UUID.randomUUID() + "@test.com";
		userRepository.save(newUser("u_" + UUID.randomUUID(), email));
		assertThat(userRepository.findByEmail(email)).isPresent();
		assertThat(userRepository.findByEmail("missing@test.com")).isEmpty();
	}
	
	@Test @DisplayName("findByEmailVerificationToken → bulundu/bulunamadı")
	void findByEmailVerificationToken_works() {
		var u = newUser("u_" + UUID.randomUUID(), "tok@test.com");
		u.setEmailVerificationToken("tok-" + UUID.randomUUID());
		userRepository.save(u);
		
		assertThat(userRepository.findByEmailVerificationToken(u.getEmailVerificationToken())).isPresent();
		assertThat(userRepository.findByEmailVerificationToken("nope-" + UUID.randomUUID())).isEmpty();
	}
	
	@Test @DisplayName("duplicate username → unique constraint ile patlamalı")
	void save_shouldFail_onDuplicateUsername() {
		var username = "dup_" + UUID.randomUUID();
		userRepository.save(newUser(username, "a@test.com"));
		
		var duplicate = newUser(username, "b@test.com");
		assertThatThrownBy(() -> userRepository.saveAndFlush(duplicate))
				.isInstanceOf(DataIntegrityViolationException.class);
	}
}