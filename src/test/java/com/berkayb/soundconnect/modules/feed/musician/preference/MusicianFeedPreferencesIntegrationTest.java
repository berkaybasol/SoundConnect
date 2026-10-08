package com.berkayb.soundconnect.modules.feed.musician.preference;

import com.berkayb.soundconnect.modules.feed.musician.preference.dto.MusicianFeedCompletionTaskCode;
import com.berkayb.soundconnect.modules.feed.musician.preference.dto.MusicianFeedPreferencesUpdate;
import com.berkayb.soundconnect.modules.feed.musician.preference.service.MusicianFeedPreferencesServiceImpl;
import com.berkayb.soundconnect.modules.instrument.entity.Instrument;
import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.enums.MediaKind;
import com.berkayb.soundconnect.modules.media.enums.MediaOwnerType;
import com.berkayb.soundconnect.modules.media.enums.MediaStatus;
import com.berkayb.soundconnect.modules.media.enums.MediaVisibility;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.track.entity.Track;
import com.berkayb.soundconnect.modules.track.enums.TrackOwnerType;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
		"spring.config.location=classpath:/application-test.yml",
		"spring.config.import=",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect"
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(MusicianFeedPreferencesServiceImpl.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MusicianFeedPreferencesIntegrationTest {
	@Container
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
			.withDatabaseName("musician_feed_preferences_integration")
			.withUsername("soundconnect").withPassword("soundconnect");

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
		registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
	}

	@Autowired EntityManager entityManager;
	@Autowired JdbcTemplate jdbc;
	@Autowired MusicianFeedPreferencesServiceImpl service;

	private UUID userId;
	private UUID profileId;
	private UUID cityId;

	@BeforeEach
	void data() {
		City city = City.builder().name("İstanbul-" + UUID.randomUUID()).build();
		entityManager.persist(city);
		Instrument guitar = Instrument.builder().name("Gitar-" + UUID.randomUUID()).build();
		entityManager.persist(guitar);
		User user = User.builder().username("feed" + UUID.randomUUID().toString().replace("-", "").substring(0, 12))
				.email(UUID.randomUUID() + "@test.invalid").password("test-password")
				.status(UserStatus.ACTIVE).emailVerified(true).build();
		entityManager.persist(user);
		MusicianProfile profile = MusicianProfile.builder().user(user).instruments(new HashSet<>(Set.of(guitar))).build();
		entityManager.persist(profile);
		entityManager.flush();
		userId = user.getId();
		profileId = profile.getId();
		cityId = city.getId();
		entityManager.clear();
	}

	@Test
	void missingPreferenceIsAReadOnlyDefaultAndCitySelectionIsStoredOutsideProfileAndLocation() {
		var initial = service.get(userId);
		assertThat(initial.version()).isZero();
		assertThat(initial.opportunityCity()).isNull();
		assertThat(initial.instruments()).hasSize(1);
		assertThat(initial.completion().personalizationReadiness().completed()).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from tbl_musician_feed_preferences", Long.class)).isZero();

		var updated = service.update(userId, new MusicianFeedPreferencesUpdate(cityId, 0L));
		assertThat(updated.version()).isEqualTo(1);
		assertThat(updated.opportunityCity().id()).isEqualTo(cityId);
		assertThat(updated.completion().personalizationReadiness().complete()).isTrue();
		assertThat(jdbc.queryForObject("select opportunity_city_id from tbl_musician_feed_preferences "
				+ "where musician_profile_id=?", UUID.class, profileId)).isEqualTo(cityId);
		assertThat(jdbc.queryForObject("select city_id from tbl_user where id=?", UUID.class, userId)).isNull();
	}

	@Test
	void onlyReadyPublicNativeMediaCompletesPortfolio() {
		MediaAsset privateAsset = asset(MediaStatus.READY, MediaVisibility.PRIVATE);
		entityManager.persist(privateAsset);
		entityManager.flush();
		entityManager.persist(Track.builder().ownerType(TrackOwnerType.MUSICIAN_PROFILE).ownerId(profileId)
				.mediaAssetId(privateAsset.getId()).title("Private demo").build());
		entityManager.flush();
		entityManager.clear();

		assertThat(service.get(userId).completion().incompleteTasks())
				.anyMatch(task -> task.code() == MusicianFeedCompletionTaskCode.PORTFOLIO);

		MediaAsset publicAsset = asset(MediaStatus.READY, MediaVisibility.PUBLIC);
		entityManager.persist(publicAsset);
		entityManager.flush();
		entityManager.persist(Track.builder().ownerType(TrackOwnerType.MUSICIAN_PROFILE).ownerId(profileId)
				.mediaAssetId(publicAsset.getId()).title("Public demo").build());
		entityManager.flush();
		entityManager.clear();

		assertThat(service.get(userId).completion().incompleteTasks())
				.noneMatch(task -> task.code() == MusicianFeedCompletionTaskCode.PORTFOLIO);
	}

	private MediaAsset asset(MediaStatus status, MediaVisibility visibility) {
		return MediaAsset.builder().kind(MediaKind.AUDIO).status(status).visibility(visibility)
				.ownerType(MediaOwnerType.MUSICIAN_PROFILE).ownerId(profileId)
				.mimeType("audio/mpeg").size(128L).build();
	}
}
