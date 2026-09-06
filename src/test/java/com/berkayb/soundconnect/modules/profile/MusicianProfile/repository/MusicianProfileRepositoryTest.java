package com.berkayb.soundconnect.modules.profile.MusicianProfile.repository;

import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.Band;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository.BandRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Testcontainers(disabledWithoutDocker = true)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Tag("repo")
class MusicianProfileRepositoryTest {
	
	@Container
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");
	
	@Autowired
	TestEntityManager em;
	
	@Autowired
	MusicianProfileRepository repository;

	@Autowired
	BandRepository bandRepository;
	
	@DynamicPropertySource
	static void props(DynamicPropertyRegistry r) {
		r.add("spring.datasource.url", postgres::getJdbcUrl);
		r.add("spring.datasource.username", postgres::getUsername);
		r.add("spring.datasource.password", postgres::getPassword);
	}
	
	@Test
	void findByUserId_shouldReturnProfile() {
		// minimal User oluştur
		var user = User.builder()
		               .username("berkay")
		               .email("u@x.com")
		               .password("pwd")
		               .build();
		user = em.persistFlushFind(user);
		
		
		var profile = MusicianProfile.builder()
		                             .user(user)
		                             .stageName("Stage")
		                             .description("Desc")
		                             .build();
		em.persistAndFlush(profile);
		
		var found = repository.findByUserId(user.getId());
		assertThat(found).isPresent();
	}

	@Test
	void performerSearchFoldsTurkishDiacriticsForMusiciansAndBands() {
		User user = em.persistFlushFind(User.builder()
				.username("bugrasahin")
				.email("bugrasahin@example.com")
				.password("pwd")
				.build());
		MusicianProfile musician = em.persistAndFlush(MusicianProfile.builder()
				.user(user)
				.stageName("Çağrı Şahin")
				.build());
		Band band = em.persistAndFlush(Band.builder().name("Şahbaz").build());
		em.clear();

		assertThat(repository.searchByStageNameOrUsername(
				"cagri sah",
				"cagri sah",
				PageRequest.of(0, 10)
		)).extracting(MusicianProfile::getId).containsExactly(musician.getId());
		assertThat(bandRepository.searchByName("sah", PageRequest.of(0, 10)))
				.extracting(Band::getId)
				.containsExactly(band.getId());
	}
}
