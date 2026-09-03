package com.berkayb.soundconnect.modules.profile.ListenerProfile.repository;

import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.entity.District;
import com.berkayb.soundconnect.modules.location.entity.Neighborhood;
import com.berkayb.soundconnect.modules.location.repository.CityRepository;
import com.berkayb.soundconnect.modules.location.repository.DistrictRepository;
import com.berkayb.soundconnect.modules.location.repository.NeighborhoodRepository;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.entity.ListenerProfile;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.role.repository.RoleRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.AuthProvider;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@EnableJpaRepositories(basePackages = "com.berkayb.soundconnect")
@EntityScan(basePackages = "com.berkayb.soundconnect")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
		"spring.datasource.url=jdbc:h2:mem:sc-${random.uuid};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
		"spring.jpa.hibernate.ddl-auto=create-drop"
})
@Tag("repo")
class ListenerProfileRepositoryTest {
	
	@Autowired ListenerProfileRepository listenerRepo;
	@Autowired UserRepository userRepo;
	@Autowired RoleRepository roleRepo;
	
	// location gerekli alanları seed’lemek için
	@Autowired CityRepository cityRepo;
	@Autowired DistrictRepository districtRepo;
	@Autowired NeighborhoodRepository neighborhoodRepo;
	
	City city;

	private String randomUsername(String prefix) {
		return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
	}
	
	@BeforeEach
	void setup() {
		// child -> parent temizliği
		listenerRepo.deleteAll();
		userRepo.deleteAll();
		roleRepo.deleteAll();
		neighborhoodRepo.deleteAll();
		districtRepo.deleteAll();
		cityRepo.deleteAll();
		
		city = cityRepo.save(City.builder().name("C_" + UUID.randomUUID()).build());
		// District/Neighborhood entity’leriniz user için zorunlu değilse oluşturmaya gerek yok;
		// ama FK cascade’leri sebebiyle yukarıda temizliği dahil ettik.
	}
	
	@Test
	void findByUserId_should_return_profile_when_exists() {
		User user = userRepo.save(User.builder()
		                              .username(randomUsername("bob_"))
		                              .email("bob_" + UUID.randomUUID() + "@t.local")
		                              .password("secret")
		                              .provider(AuthProvider.LOCAL)
		                              .emailVerified(true)
		                              .city(city)
		                              .build());
		
		ListenerProfile profile = listenerRepo.save(ListenerProfile.builder()
		                                                           .user(user)
		                                                           .description("desc")
		                                                           .profilePictureMediaId(UUID.randomUUID())
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

	@Test
	void pendingIdentityQueryIncludesIncompleteAndMissingListenerProfilesOnly() {
		Role listenerRole = roleRepo.save(Role.builder().name("ROLE_LISTENER").build());
		Role musicianRole = roleRepo.save(Role.builder().name("ROLE_MUSICIAN").build());
		User incomplete = saveUserWithRole("incomplete", listenerRole);
		User completed = saveUserWithRole("completed", listenerRole);
		User missing = saveUserWithRole("missing", listenerRole);
		User musician = saveUserWithRole("musician", musicianRole);

		listenerRepo.save(ListenerProfile.builder()
				.user(incomplete)
				.visibilityMode(ListenerVisibilityMode.STANDARD)
				.visibilityChoiceCompleted(false)
				.build());
		listenerRepo.save(ListenerProfile.builder()
				.user(completed)
				.visibilityMode(ListenerVisibilityMode.GHOST)
				.visibilityChoiceCompleted(true)
				.build());
		listenerRepo.flush();

		assertThat(listenerRepo.findUserIdsRequiringVisibilityChoice(List.of(
				incomplete.getId(), completed.getId(), missing.getId(), musician.getId()
		))).containsExactlyInAnyOrder(incomplete.getId(), missing.getId());
	}

	private User saveUserWithRole(String prefix, Role role) {
		return userRepo.save(User.builder()
				.username(randomUsername(prefix + "_"))
				.email(prefix + "_" + UUID.randomUUID() + "@t.local")
				.password("secret")
				.provider(AuthProvider.LOCAL)
				.emailVerified(true)
				.roles(Set.of(role))
				.city(city)
				.build());
	}

	@Test
	void visibilityModeDefaultsToStandardAndCanBeLockedByUserOrProfileId() {
		User user = userRepo.save(User.builder()
		                              .username(randomUsername("visibility_"))
		                              .email("visibility_" + UUID.randomUUID() + "@t.local")
		                              .password("secret")
		                              .provider(AuthProvider.LOCAL)
		                              .emailVerified(true)
		                              .city(city)
		                              .build());
		ListenerProfile profile = listenerRepo.saveAndFlush(ListenerProfile.builder()
		                                                                   .user(user)
		                                                                   .description("bio")
		                                                                   .build());

		assertThat(profile.getVisibilityMode()).isEqualTo(ListenerVisibilityMode.STANDARD);
		assertThat(profile.isVisibilityChoiceCompleted()).isFalse();
		assertThat(listenerRepo.existsByUserIdAndVisibilityMode(user.getId(), ListenerVisibilityMode.STANDARD))
				.isTrue();
		assertThat(listenerRepo.existsByUserIdAndVisibilityChoiceCompletedTrue(user.getId()))
				.isFalse();
		assertThat(listenerRepo.findForPublicIdentityByUserId(user.getId())).isEmpty();
		profile.setVisibilityChoiceCompleted(true);
		listenerRepo.saveAndFlush(profile);
		assertThat(listenerRepo.existsByUserIdAndVisibilityChoiceCompletedTrue(user.getId()))
				.isTrue();
		assertThat(listenerRepo.findForPublicIdentityByUserId(user.getId())).contains(profile);
		assertThat(listenerRepo.findByUserIdForUpdate(user.getId())).contains(profile);
		assertThat(listenerRepo.findByIdForUpdate(profile.getId())).contains(profile);
		assertThat(listenerRepo.findByUserIdForVisibilityRead(user.getId())).contains(profile);
		assertThat(listenerRepo.findByIdForVisibilityRead(profile.getId())).contains(profile);
	}

	@Test
	void ghostVisibilityRoundTripsAndProfileLookupRecognizesIt() {
		User user = userRepo.save(User.builder()
		                              .username(randomUsername("ghost_"))
		                              .email("ghost_" + UUID.randomUUID() + "@t.local")
		                              .password("secret")
		                              .provider(AuthProvider.LOCAL)
		                              .emailVerified(true)
		                              .city(city)
		                              .build());
		ListenerProfile profile = listenerRepo.saveAndFlush(ListenerProfile.builder()
		                                                                   .user(user)
		                                                                   .visibilityMode(ListenerVisibilityMode.GHOST)
		                                                                   .build());
		assertThat(listenerRepo.existsByIdAndVisibilityMode(profile.getId(), ListenerVisibilityMode.GHOST)).isTrue();
		assertThat(listenerRepo.findById(profile.getId()))
				.get()
				.extracting(ListenerProfile::getVisibilityMode)
				.isEqualTo(ListenerVisibilityMode.GHOST);
	}

	@Test
	void batchVisibilityProjectionReturnsOnlyGhostUsers() {
		User ghost = userRepo.save(User.builder()
				.username(randomUsername("batch_ghost_"))
				.email("batch_ghost_" + UUID.randomUUID() + "@t.local")
				.password("secret").provider(AuthProvider.LOCAL).emailVerified(true).city(city).build());
		User standard = userRepo.save(User.builder()
				.username(randomUsername("batch_standard_"))
				.email("batch_standard_" + UUID.randomUUID() + "@t.local")
				.password("secret").provider(AuthProvider.LOCAL).emailVerified(true).city(city).build());
		listenerRepo.saveAllAndFlush(List.of(
				ListenerProfile.builder().user(ghost)
						.visibilityMode(ListenerVisibilityMode.GHOST).build(),
				ListenerProfile.builder().user(standard)
						.visibilityMode(ListenerVisibilityMode.STANDARD).build()
		));

		assertThat(listenerRepo.findUserIdsByVisibilityMode(
				Set.of(ghost.getId(), standard.getId(), UUID.randomUUID()),
				ListenerVisibilityMode.GHOST))
				.containsExactly(ghost.getId());
	}

	@Test
	void usernameSearchTreatsUnderscoreAndPercentAsLiteralCharacters() {
		User literal = userRepo.save(User.builder()
				.username("literal_user%one")
				.email("literal_" + UUID.randomUUID() + "@t.local")
				.password("secret")
				.provider(AuthProvider.LOCAL)
				.emailVerified(true)
				.city(city)
				.build());
		User wildcardLookalike = userRepo.save(User.builder()
				.username("literalxuseryone")
				.email("lookalike_" + UUID.randomUUID() + "@t.local")
				.password("secret")
				.provider(AuthProvider.LOCAL)
				.emailVerified(true)
				.city(city)
				.build());
		ListenerProfile literalProfile = listenerRepo.save(ListenerProfile.builder()
				.user(literal)
				.description("plain")
				.build());
		listenerRepo.save(ListenerProfile.builder()
				.user(wildcardLookalike)
				.description("plain")
				.build());

		List<ListenerProfile> result =
				listenerRepo.searchByUsernameOrBio("_user%", "_user%", PageRequest.of(0, 10));

		assertThat(result).extracting(ListenerProfile::getId)
				.containsExactly(literalProfile.getId());
	}

	@Test
	void usernameSearchStopsMatchingTheOldNameImmediatelyAfterRename() {
		User user = userRepo.save(User.builder()
				.username("oldname")
				.email("rename_" + UUID.randomUUID() + "@t.local")
				.password("secret")
				.provider(AuthProvider.LOCAL)
				.emailVerified(true)
				.city(city)
				.build());
		ListenerProfile profile = listenerRepo.save(ListenerProfile.builder()
				.user(user)
				.description("plain")
				.build());

		assertThat(listenerRepo.searchByUsernameOrBio("oldname", "oldname", PageRequest.of(0, 10)))
				.extracting(ListenerProfile::getId)
				.containsExactly(profile.getId());

		user.setUsername("newname");
		userRepo.saveAndFlush(user);

		assertThat(listenerRepo.searchByUsernameOrBio("oldname", "oldname", PageRequest.of(0, 10))).isEmpty();
		assertThat(listenerRepo.searchByUsernameOrBio("newname", "newname", PageRequest.of(0, 10)))
				.extracting(ListenerProfile::getId)
				.containsExactly(profile.getId());
	}
}
