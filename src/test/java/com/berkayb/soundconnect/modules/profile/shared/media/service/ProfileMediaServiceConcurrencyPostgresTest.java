package com.berkayb.soundconnect.modules.profile.shared.media.service;

import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.enums.MediaKind;
import com.berkayb.soundconnect.modules.media.enums.MediaOwnerType;
import com.berkayb.soundconnect.modules.media.enums.MediaStatus;
import com.berkayb.soundconnect.modules.media.enums.MediaVisibility;
import com.berkayb.soundconnect.modules.media.repository.MediaAssetRepository;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.entity.ListenerProfile;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.repository.ListenerProfileRepository;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.support.ListenerVisibilityPolicy;
import com.berkayb.soundconnect.modules.profile.shared.media.entity.ProfileMedia;
import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileMediaRole;
import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileType;
import com.berkayb.soundconnect.modules.profile.shared.media.repository.ProfileMediaRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ProfileMediaServiceImpl.class, ListenerVisibilityPolicy.class})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ProfileMediaServiceConcurrencyPostgresTest {

	@Container
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
			.withDatabaseName("soundconnect_profile_media_service")
			.withUsername("soundconnect")
			.withPassword("soundconnect");

	@DynamicPropertySource
	static void databaseProperties(DynamicPropertyRegistry properties) {
		properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		properties.add("spring.datasource.username", POSTGRES::getUsername);
		properties.add("spring.datasource.password", POSTGRES::getPassword);
		properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
		properties.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
		properties.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
		properties.add(
				"spring.jpa.properties.hibernate.dialect",
				() -> "org.hibernate.dialect.PostgreSQLDialect");
	}

	@Autowired ProfileMediaService profileMediaService;
	@Autowired ProfileMediaRepository profileMediaRepository;
	@Autowired MediaAssetRepository mediaAssetRepository;
	@Autowired ListenerProfileRepository listenerProfileRepository;
	@Autowired UserRepository userRepository;

	@Test
	void concurrentSameAttachmentReturnsOneAuthoritativeRowToBothCallers() throws Exception {
		User user = userRepository.saveAndFlush(User.builder()
				.username("profile-media-concurrency")
				.password("unused")
				.email("profile-media-concurrency@example.test")
				.status(UserStatus.ACTIVE)
				.emailVerified(true)
				.build());
		ListenerProfile profile = listenerProfileRepository.saveAndFlush(
				ListenerProfile.builder()
						.user(user)
						.name("Concurrency")
						.visibilityChoiceCompleted(true)
						.build());
		MediaAsset asset = mediaAssetRepository.saveAndFlush(MediaAsset.builder()
				.kind(MediaKind.IMAGE)
				.status(MediaStatus.READY)
				.visibility(MediaVisibility.PUBLIC)
				.ownerType(MediaOwnerType.LISTENER_PROFILE)
				.ownerId(profile.getId())
				.sourceUrl("https://cdn.example.test/concurrent.jpg")
				.mimeType("image/jpeg")
				.size(42L)
				.build());

		CountDownLatch start = new CountDownLatch(1);
		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			Future<ProfileMedia> first = executor.submit(() -> {
				start.await();
				return profileMediaService.addMedia(
						user.getId(), ProfileType.LISTENER, profile.getId(), asset.getId(),
						ProfileMediaRole.GALLERY, 0);
			});
			Future<ProfileMedia> second = executor.submit(() -> {
				start.await();
				return profileMediaService.addMedia(
						user.getId(), ProfileType.LISTENER, profile.getId(), asset.getId(),
						ProfileMediaRole.GALLERY, 99);
			});
			start.countDown();

			List<ProfileMedia> results = List.of(first.get(), second.get());
			assertThat(results).extracting(ProfileMedia::getId).containsOnly(results.getFirst().getId());
		}

		List<ProfileMedia> rows = profileMediaRepository.findAll();
		assertThat(rows).hasSize(1);
		assertThat(rows.getFirst().getOrderIndex()).isIn(0, 99);
	}
}
