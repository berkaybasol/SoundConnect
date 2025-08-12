package com.berkayb.soundconnect.modules.profile.OrganizerProfile.repository;

import com.berkayb.soundconnect.modules.profile.OrganizerProfile.entity.OrganizerProfile;
import com.berkayb.soundconnect.modules.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.autoconfigure.domain.EntityScan;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@EnableJpaRepositories(basePackages = "com.berkayb.soundconnect")
@EntityScan(basePackages = "com.berkayb.soundconnect")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class OrganizerProfileRepositoryTest {
	
	@Autowired OrganizerProfileRepository organizerRepo;
	@Autowired com.berkayb.soundconnect.modules.user.repository.UserRepository userRepo;
	
	@Test
	void findByUserId_should_return_profile_when_exists() {
		User user = userRepo.save(User.builder()
		                              .username("bob")
		                              .password("secret")
		                              .build());
		
		OrganizerProfile profile = organizerRepo.save(OrganizerProfile.builder()
		                                                              .user(user)
		                                                              .name("OrgBob")
		                                                              .description("desc")
		                                                              .profilePicture("pp.png")
		                                                              .address("addr")
		                                                              .phone("555")
		                                                              .instagramUrl("ig")
		                                                              .youtubeUrl("yt")
		                                                              .build());
		
		Optional<OrganizerProfile> found = organizerRepo.findByUserId(user.getId());
		assertThat(found).isPresent();
		assertThat(found.get().getId()).isEqualTo(profile.getId());
		assertThat(found.get().getName()).isEqualTo("OrgBob");
	}
	
	@Test
	void findOrganizerProfileByName_should_work() {
		User user = userRepo.save(User.builder()
		                              .username("kate")
		                              .password("pw")
		                              .build());
		
		organizerRepo.save(OrganizerProfile.builder()
		                                   .user(user)
		                                   .name("FindMe")
		                                   .description("d")
		                                   .profilePicture("p.png")
		                                   .address("a")
		                                   .phone("1")
		                                   .instagramUrl("ig")
		                                   .youtubeUrl("yt")
		                                   .build());
		
		var found = organizerRepo.findOrganizerProfileByName("FindMe");
		assertThat(found).isPresent();
		assertThat(found.get().getName()).isEqualTo("FindMe");
	}
	
	@Test
	void findByUserId_should_return_empty_when_not_exists() {
		Optional<OrganizerProfile> found = organizerRepo.findByUserId(UUID.randomUUID());
		assertThat(found).isEmpty();
	}
}