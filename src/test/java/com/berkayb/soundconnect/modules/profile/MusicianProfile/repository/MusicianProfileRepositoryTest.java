package com.berkayb.soundconnect.modules.profile.MusicianProfile.repository;

import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.Band;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository.BandRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/** No default/local datasource is ever instantiated for this repository slice. */
@DataJpaTest(properties = {
		"spring.config.location=classpath:/application-test.yml",
		"spring.config.import=",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect"
})
@ActiveProfiles("test")
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = MusicianProfileRepositoryTest.RepositoryTestConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Tag("repo")
class MusicianProfileRepositoryTest {
	
	@Container
	static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4-alpine")
			.withDatabaseName("musician_profile_repository_test")
			.withUsername("repository_test").withPassword("repository_test").withReuse(false);

	@Autowired
	DataSource dataSource;
	
	@Autowired
	TestEntityManager em;
	
	@Autowired
	MusicianProfileRepository repository;

	@Autowired
	BandRepository bandRepository;
	
	@BeforeEach
	void datasourceMustBeTheDisposableContainerBeforeFixtureWrites() throws SQLException {
		assertThat(postgres.isRunning()).isTrue();
		try (var connection = dataSource.getConnection()) {
			assertThat(connection.getMetaData().getURL()).isEqualTo(postgres.getJdbcUrl());
			assertThat(connection.getCatalog()).isEqualTo(postgres.getDatabaseName());
		}
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

	@Configuration(proxyBeanMethods = false)
	@EnableJpaAuditing
	@EnableJpaRepositories(basePackageClasses = {MusicianProfileRepository.class, BandRepository.class})
	@EntityScan(basePackages = "com.berkayb.soundconnect")
	static class RepositoryTestConfiguration {
		@Bean
		DataSource dataSource() {
			if (!postgres.isRunning()) throw new IllegalStateException("Disposable PostgreSQL must be running");
			// No datasource property binding and no fallback when Docker is unavailable.
			return new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
		}
	}
}
