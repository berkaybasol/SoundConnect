package com.berkayb.soundconnect.modules.profile.shared.resolver.service;

import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.support.ListenerVisibilityPolicy;
import com.berkayb.soundconnect.modules.profile.shared.resolver.contributor.PublicProfileContributor;
import com.berkayb.soundconnect.modules.profile.shared.resolver.dto.UserProfileTargetDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PublicProfileResolverServiceImplTest {

	@AfterEach void clearViewer() { SecurityContextHolder.clearContext(); }

	@Test void listenerGetsOnlyAGateMarkerWhenAStudioAvatarTargetIsRestricted() throws Exception {
		viewer("ROLE_LISTENER");
		UUID userId = UUID.randomUUID(), studioId = UUID.randomUUID();
		var result = resolve(userId, List.of(new UserProfileTargetDto("STUDIO", studioId,
				"Private studio name", "https://studio.test/private-avatar.jpg")),
				ListenerVisibilityPolicy.PublicVisibilityRestrictions.empty());
		assertThat(result.profiles()).isEmpty();
		assertThat(result.accessRestriction()).isEqualTo("STUDIO_MAINSTAGE_RESTRICTED");
		String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result);
		assertThat(json).contains("STUDIO_MAINSTAGE_RESTRICTED")
				.doesNotContain(studioId.toString(), "Private studio name", "private-avatar.jpg");
	}

	@Test void ghostAndPendingTargetsNeverDiscloseAStudioRestrictionMarker() {
		viewer("ROLE_LISTENER");
		UUID userId = UUID.randomUUID();
		var studio = new UserProfileTargetDto("STUDIO", UUID.randomUUID(), "Hidden studio", null);
		for (var restrictions : List.of(
				new ListenerVisibilityPolicy.PublicVisibilityRestrictions(Set.of(userId), Set.of()),
				new ListenerVisibilityPolicy.PublicVisibilityRestrictions(Set.of(), Set.of(userId)))) {
			var result = resolve(userId, List.of(studio), restrictions);
			assertThat(result.profiles()).isEmpty();
			assertThat(result.accessRestriction()).isNull();
		}
	}

	@Test void ghostContributorWinsOverCorruptStudioTargetEvenWithoutAuthoritativeRestriction() {
		viewer("ROLE_LISTENER");
		var ghost = new UserProfileTargetDto("LISTENER", UUID.randomUUID(), "ghost", null, ListenerVisibilityMode.GHOST);
		var result = resolve(UUID.randomUUID(), List.of(ghost,
				new UserProfileTargetDto("STUDIO", UUID.randomUUID(), "Hidden studio", null)),
				ListenerVisibilityPolicy.PublicVisibilityRestrictions.empty());
		assertThat(result.profiles()).containsExactly(ghost);
		assertThat(result.accessRestriction()).isNull();
	}

	@Test void mixedPublicTargetsAndGenuinelyMissingTargetsDoNotProduceTheStudioOnlyMarker() {
		viewer("ROLE_LISTENER");
		var musician = new UserProfileTargetDto("MUSICIAN", UUID.randomUUID(), "Artist", null);
		var mixed = resolve(UUID.randomUUID(), List.of(musician,
				new UserProfileTargetDto("STUDIO", UUID.randomUUID(), "Hidden studio", null)),
				ListenerVisibilityPolicy.PublicVisibilityRestrictions.empty());
		assertThat(mixed.profiles()).containsExactly(musician);
		assertThat(mixed.accessRestriction()).isNull();
		assertThat(resolve(UUID.randomUUID(), List.of(),
				ListenerVisibilityPolicy.PublicVisibilityRestrictions.empty()).accessRestriction()).isNull();
	}

	@org.junit.jupiter.params.ParameterizedTest
	@org.junit.jupiter.params.provider.ValueSource(strings = {"ROLE_MUSICIAN", "ROLE_VENUE", "ROLE_STUDIO", "GUEST"})
	void backstageAndGuestStudioResolutionKeepTheExistingContract(String role) {
		if (!role.equals("GUEST")) viewer(role);
		var studio = new UserProfileTargetDto("STUDIO", UUID.randomUUID(), "Studio", null);
		var result = resolve(UUID.randomUUID(), List.of(studio),
				ListenerVisibilityPolicy.PublicVisibilityRestrictions.empty());
		assertThat(result.profiles()).containsExactly(studio);
		assertThat(result.accessRestriction()).isNull();
	}

	@Test void legacyConstructorKeepsAbsentRestrictionOutOfJson() {
		var value = new com.berkayb.soundconnect.modules.profile.shared.resolver.dto.UserProfilesResolveResponseDto(
				UUID.randomUUID(), List.of());
		var json = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(value);
		assertThat(json.has("userId")).isTrue();
		assertThat(json.has("profiles")).isTrue();
		assertThat(json.has("accessRestriction")).isFalse();
	}

	@Test void restrictedResolutionUsesPrivateNoStoreResponse() {
		var service = mock(PublicProfileResolverService.class);
		UUID userId = UUID.randomUUID();
		var value = new com.berkayb.soundconnect.modules.profile.shared.resolver.dto.UserProfilesResolveResponseDto(
				userId, List.of(), "STUDIO_MAINSTAGE_RESTRICTED");
		when(service.resolveByUserId(userId)).thenReturn(value);
		var response = new com.berkayb.soundconnect.modules.profile.shared.resolver.controller.publicapi.PublicProfileResolverController(service)
				.resolveByUser(userId);
		assertThat(response.getHeaders().getCacheControl()).contains("private", "no-store");
		assertThat(response.getBody().getData()).isEqualTo(value);
	}

	private void viewer(String role) {
		SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
				"viewer", "n/a", List.of(new SimpleGrantedAuthority(role))));
	}

	private com.berkayb.soundconnect.modules.profile.shared.resolver.dto.UserProfilesResolveResponseDto resolve(
			UUID userId, List<UserProfileTargetDto> targets,
			ListenerVisibilityPolicy.PublicVisibilityRestrictions restrictions) {
		PublicProfileContributor contributor = mock(PublicProfileContributor.class);
		ListenerVisibilityPolicy policy = mock(ListenerVisibilityPolicy.class);
		when(policy.publicVisibilityRestrictions(List.of(userId))).thenReturn(restrictions);
		when(contributor.resolve(userId)).thenReturn(targets);
		return new PublicProfileResolverServiceImpl(List.of(contributor), policy).resolveByUserId(userId);
	}

	@Test
	void sortsBandBetweenMusicianAndVenue() {
		UUID userId = UUID.randomUUID();
		PublicProfileContributor contributor = mock(PublicProfileContributor.class);
		ListenerVisibilityPolicy visibilityPolicy = mock(ListenerVisibilityPolicy.class);
		when(visibilityPolicy.publicVisibilityRestrictions(List.of(userId)))
				.thenReturn(ListenerVisibilityPolicy.PublicVisibilityRestrictions.empty());
		when(contributor.resolve(userId)).thenReturn(List.of(
				new UserProfileTargetDto("STUDIO", UUID.randomUUID(), "Studio", null),
				new UserProfileTargetDto("VENUE", UUID.randomUUID(), "Venue", null),
				new UserProfileTargetDto("BAND", UUID.randomUUID(), "Band", null),
				new UserProfileTargetDto("MUSICIAN", UUID.randomUUID(), "Musician", null)
		));

		PublicProfileResolverServiceImpl service = new PublicProfileResolverServiceImpl(
				List.of(contributor), visibilityPolicy);

		assertThat(service.resolveByUserId(userId).profiles())
				.extracting(UserProfileTargetDto::type)
				.containsExactly("MUSICIAN", "BAND", "VENUE", "STUDIO");
	}

	@Test
	void ghostListenerFailsClosedWhenLegacyDataAlsoResolvesPublicProfiles() {
		UUID userId = UUID.randomUUID();
		UserProfileTargetDto ghostListener = new UserProfileTargetDto(
				"LISTENER",
				UUID.randomUUID(),
				"ghosthandle",
				"https://cdn.example/avatar.jpg",
				ListenerVisibilityMode.GHOST
		);
		PublicProfileContributor contributor = mock(PublicProfileContributor.class);
		ListenerVisibilityPolicy visibilityPolicy = mock(ListenerVisibilityPolicy.class);
		when(visibilityPolicy.publicVisibilityRestrictions(List.of(userId)))
				.thenReturn(new ListenerVisibilityPolicy.PublicVisibilityRestrictions(
						Set.of(userId), Set.of()));
		when(contributor.resolve(userId)).thenReturn(List.of(
				new UserProfileTargetDto("MUSICIAN", UUID.randomUUID(), "Legacy Artist", null),
				new UserProfileTargetDto("BAND", UUID.randomUUID(), "Legacy Band", null),
				ghostListener
		));

		PublicProfileResolverServiceImpl service = new PublicProfileResolverServiceImpl(
				List.of(contributor), visibilityPolicy);

		assertThat(service.resolveByUserId(userId).profiles()).containsExactly(ghostListener);
	}

	@Test
	void pendingListenerChoiceSuppressesEveryLegacyPublicProfileTarget() {
		UUID userId = UUID.randomUUID();
		PublicProfileContributor contributor = mock(PublicProfileContributor.class);
		ListenerVisibilityPolicy visibilityPolicy = mock(ListenerVisibilityPolicy.class);
		when(visibilityPolicy.publicVisibilityRestrictions(List.of(userId)))
				.thenReturn(new ListenerVisibilityPolicy.PublicVisibilityRestrictions(
						Set.of(), Set.of(userId)));
		PublicProfileResolverServiceImpl service = new PublicProfileResolverServiceImpl(
				List.of(contributor), visibilityPolicy);

		assertThat(service.resolveByUserId(userId).profiles()).isEmpty();
		verifyNoInteractions(contributor);
	}

	@Test
	void authoritativeGhostSnapshotFailsClosedWhenListenerContributorIsMissing() {
		UUID userId = UUID.randomUUID();
		PublicProfileContributor contributor = mock(PublicProfileContributor.class);
		ListenerVisibilityPolicy visibilityPolicy = mock(ListenerVisibilityPolicy.class);
		when(visibilityPolicy.publicVisibilityRestrictions(List.of(userId)))
				.thenReturn(new ListenerVisibilityPolicy.PublicVisibilityRestrictions(
						Set.of(userId), Set.of()));
		when(contributor.resolve(userId)).thenReturn(List.of(
				new UserProfileTargetDto(
						"MUSICIAN", UUID.randomUUID(), "Must stay hidden", null)
		));
		PublicProfileResolverServiceImpl service = new PublicProfileResolverServiceImpl(
				List.of(contributor), visibilityPolicy);

		assertThat(service.resolveByUserId(userId).profiles()).isEmpty();
	}
}
