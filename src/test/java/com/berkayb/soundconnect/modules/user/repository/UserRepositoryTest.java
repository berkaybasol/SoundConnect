package com.berkayb.soundconnect.modules.user.repository;

import com.berkayb.soundconnect.modules.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ActiveProfiles("test")
@Tag("repo")
class UserRepositoryTest {
	
	@Autowired
	UserRepository userRepository;
	
	private User newUser(String username, String email) {
		return User.builder()
		           .username(username)
		           .password("secret")
		           .email(email)
		           // provider & emailVerified builder default’ları yeterli
		           .build();
	}
	
	@Test
	@DisplayName("findByUsername → kullanıcı bulundu")
	void findByUsername_found() {
		var u = userRepository.save(newUser("user_" + UUID.randomUUID(), "a@test.com"));
		var found = userRepository.findByUsername(u.getUsername());
		assertThat(found).isPresent();
		assertThat(found.get().getId()).isEqualTo(u.getId());
	}
	
	@Test
	@DisplayName("findByUsername → kullanıcı yok")
	void findByUsername_notFound() {
		var found = userRepository.findByUsername("nope-" + UUID.randomUUID());
		assertThat(found).isEmpty();
	}
	
	@Test
	@DisplayName("existsByUsername → true/false")
	void existsByUsername_works() {
		var name = "exists_" + UUID.randomUUID();
		userRepository.save(newUser(name, "x@test.com"));
		assertThat(userRepository.existsByUsername(name)).isTrue();
		assertThat(userRepository.existsByUsername("other_" + UUID.randomUUID())).isFalse();
	}
	
	@Test
	@DisplayName("existsByEmail → true/false")
	void existsByEmail_works() {
		var email = "e" + UUID.randomUUID() + "@test.com";
		userRepository.save(newUser("u_" + UUID.randomUUID(), email));
		assertThat(userRepository.existsByEmail(email)).isTrue();
		assertThat(userRepository.existsByEmail("none@test.com")).isFalse();
	}
	
	@Test
	@DisplayName("findByEmail → bulundu/bulunamadı")
	void findByEmail_works() {
		var email = "f" + UUID.randomUUID() + "@test.com";
		var saved = userRepository.save(newUser("u_" + UUID.randomUUID(), email));
		assertThat(userRepository.findByEmail(email)).isPresent();
		assertThat(userRepository.findByEmail("missing@test.com")).isEmpty();
	}
	
	@Test
	@DisplayName("findByEmailVerificationToken → bulundu/bulunamadı")
	void findByEmailVerificationToken_works() {
		var u = newUser("u_" + UUID.randomUUID(), "tok@test.com");
		u.setEmailVerificationToken("tok-" + UUID.randomUUID());
		userRepository.save(u);
		
		assertThat(userRepository.findByEmailVerificationToken(u.getEmailVerificationToken()))
				.isPresent();
		assertThat(userRepository.findByEmailVerificationToken("nope-" + UUID.randomUUID()))
				.isEmpty();
	}
	
	@Test
	@DisplayName("duplicate username → unique constraint ile patlamalı")
	void save_shouldFail_onDuplicateUsername() {
		var username = "dup_" + UUID.randomUUID();
		userRepository.save(newUser(username, "a@test.com"));
		
		// aynı username, farklı email → unique (user_name) yüzünden patlar
		var duplicate = newUser(username, "b@test.com");
		
		assertThatThrownBy(() -> {
			userRepository.saveAndFlush(duplicate); // flush: constraint’i hemen tetikle
		}).isInstanceOf(DataIntegrityViolationException.class);
	}
}