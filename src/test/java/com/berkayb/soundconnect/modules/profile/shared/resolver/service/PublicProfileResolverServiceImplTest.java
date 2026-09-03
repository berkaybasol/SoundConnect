package com.berkayb.soundconnect.modules.profile.shared.resolver.service;

import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.support.ListenerVisibilityPolicy;
import com.berkayb.soundconnect.modules.profile.shared.resolver.contributor.PublicProfileContributor;
import com.berkayb.soundconnect.modules.profile.shared.resolver.dto.UserProfileTargetDto;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PublicProfileResolverServiceImplTest {

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
