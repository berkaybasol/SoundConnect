package com.berkayb.soundconnect.modules.profile.ListenerProfile.repository;

import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.repository.CityRepository;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.entity.ListenerProfile;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.AuthProvider;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@EnableJpaRepositories(basePackages = "com.berkayb.soundconnect")
@EntityScan(basePackages = "com.berkayb.soundconnect")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
		"spring.datasource.url=jdbc:h2:mem:listener-public-search-${random.uuid};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
		"spring.jpa.hibernate.ddl-auto=create-drop"
})
@Tag("repo")
class ListenerProfilePublicDiscoveryRepositoryTest {

	@Autowired ListenerProfileRepository listenerProfileRepository;
	@Autowired UserRepository userRepository;
	@Autowired CityRepository cityRepository;

	private City city;

	@BeforeEach
	void setUp() {
		listenerProfileRepository.deleteAll();
		userRepository.deleteAll();
		cityRepository.deleteAll();
		city = cityRepository.save(City.builder().name("City-" + UUID.randomUUID()).build());
	}

	@Test
	void ghostHiddenProfileCopyCannotMatchButUsernameRemainsDiscoverable() {
		ListenerProfile ghost = saveListener(
				"ghosthandle",
				"Secret Stage Name",
				"Obscure biography phrase",
				ListenerVisibilityMode.GHOST
		);
		ListenerProfile standard = saveListener(
				"standardhandle",
				"Visible Stage Name",
				"Visible biography phrase",
				ListenerVisibilityMode.STANDARD
		);

		assertThat(search("Secret Stage Name", "secret stage name")).isEmpty();
		assertThat(search("Obscure biography phrase", "obscure biography phrase")).isEmpty();
		assertThat(search("ghosthandle", "ghosthandle"))
				.extracting(ListenerProfile::getId)
				.containsExactly(ghost.getId());
		assertThat(search("Visible biography phrase", "visible biography phrase"))
				.extracting(ListenerProfile::getId)
				.containsExactly(standard.getId());
	}

	@Test
	void pendingChoiceCannotMatchByUsernameNameOrBiography() {
		saveListener(
				"pendinghandle",
				"Pending Stage Name",
				"Pending biography phrase",
				ListenerVisibilityMode.STANDARD,
				false
		);

		assertThat(search("pendinghandle", "pendinghandle")).isEmpty();
		assertThat(search("Pending Stage Name", "pending stage name")).isEmpty();
		assertThat(search("Pending biography phrase", "pending biography phrase")).isEmpty();
	}

	private java.util.List<ListenerProfile> search(String query, String usernameQuery) {
		return listenerProfileRepository.searchForPublicDiscovery(
				query,
				usernameQuery,
				ListenerVisibilityMode.GHOST,
				PageRequest.of(0, 10)
		);
	}

	private ListenerProfile saveListener(
			String username,
			String name,
			String description,
			ListenerVisibilityMode visibilityMode
	) {
		return saveListener(username, name, description, visibilityMode, true);
	}

	private ListenerProfile saveListener(
			String username,
			String name,
			String description,
			ListenerVisibilityMode visibilityMode,
			boolean visibilityChoiceCompleted
	) {
		User user = userRepository.save(User.builder()
				.username(username)
				.email(username + "-" + UUID.randomUUID() + "@soundconnect.test")
				.password("test-password-hash")
				.provider(AuthProvider.LOCAL)
				.status(com.berkayb.soundconnect.modules.user.enums.UserStatus.ACTIVE)
				.emailVerified(true)
				.city(city)
				.build());
		return listenerProfileRepository.saveAndFlush(ListenerProfile.builder()
				.user(user)
				.name(name)
				.description(description)
				.visibilityMode(visibilityMode)
				.visibilityChoiceCompleted(visibilityChoiceCompleted)
				.build());
	}
}
