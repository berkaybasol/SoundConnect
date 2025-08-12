package com.berkayb.soundconnect.modules.profile.StudioProfille.repository;

import com.berkayb.soundconnect.modules.profile.StudioProfile.entity.StudioProfile;
import com.berkayb.soundconnect.modules.profile.StudioProfile.repository.StudioProfileRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
class StudioProfileRepositoryTest {
	
	@Autowired TestEntityManager em;
	@Autowired
	StudioProfileRepository repository;
	
	@Test
	@DisplayName("findByUserId should return profile when exists")
	void findByUserId_should_return_profile_when_exists() {
		User user = User.builder()
		                .username("studioUser")
		                .password("pwd")
		                .build();
		em.persist(user);
		
		StudioProfile profile = StudioProfile.builder()
		                                     .user(user)
		                                     .description("desc")
		                                     .profilePicture("pp.png")
		                                     .facilities(Set.of("Piano"))
		                                     .build();
		repository.save(profile);
		
		Optional<StudioProfile> found = repository.findByUserId(user.getId());
		assertThat(found).isPresent();
		assertThat(found.get().getDescription()).isEqualTo("desc");
		assertThat(found.get().getFacilities()).containsExactly("Piano");
	}
	
	@Test
	@DisplayName("findByUserId should return empty when not exists")
	void findByUserId_should_return_empty_when_not_exists() {
		Optional<StudioProfile> found = repository.findByUserId(java.util.UUID.randomUUID());
		assertThat(found).isEmpty();
	}
}