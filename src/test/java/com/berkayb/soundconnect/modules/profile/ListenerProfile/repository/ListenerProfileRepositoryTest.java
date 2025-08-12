package com.berkayb.soundconnect.modules.profile.ListenerProfile.repository;

import com.berkayb.soundconnect.SoundConnectApplication;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.entity.ListenerProfile;
import com.berkayb.soundconnect.modules.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@EnableJpaRepositories(basePackages = "com.berkayb.soundconnect")
@EntityScan(basePackages = "com.berkayb.soundconnect")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ListenerProfileRepositoryTest {
	
	@Autowired ListenerProfileRepository listenerRepo;
	@Autowired com.berkayb.soundconnect.modules.user.repository.UserRepository userRepo;
	
	@Test
	void findByUserId_should_return_profile_when_exists() {
		User user = userRepo.save(User.builder()
		                              .username("bob")
		                              .password("secret")
		                              .build());
		
		ListenerProfile profile = listenerRepo.save(ListenerProfile.builder()
		                                                           .user(user)
		                                                           .description("desc")
		                                                           .profilePicture("pp.png")
		                                                           .build());
		
		Optional<ListenerProfile> found = listenerRepo.findByUserId(user.getId());
		assertThat(found).isPresent();
		assertThat(found.get().getId()).isEqualTo(profile.getId());
	}
	
	@Test
	void findByUserId_should_return_empty_when_not_exists() {
		Optional<ListenerProfile> found = listenerRepo.findByUserId(UUID.randomUUID());
		assertThat(found).isEmpty();
	}
}