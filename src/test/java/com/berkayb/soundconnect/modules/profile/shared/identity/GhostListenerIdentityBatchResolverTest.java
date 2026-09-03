package com.berkayb.soundconnect.modules.profile.shared.identity;

import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.repository.ListenerProfileRepository;
import com.berkayb.soundconnect.modules.profile.shared.avatar.PersonalProfileAvatarCandidate;
import com.berkayb.soundconnect.modules.profile.shared.avatar.PersonalProfileAvatarRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GhostListenerIdentityBatchResolverTest {

	@Mock
	private PersonalProfileAvatarRepository identityRepository;

	@Mock
	private MediaAssetService mediaAssetService;

	@Mock
	private ListenerProfileRepository listenerProfileRepository;

	private GhostListenerIdentityBatchResolver resolver;

	@BeforeEach
	void setUp() {
		resolver = new GhostListenerIdentityBatchResolver(
				identityRepository, mediaAssetService, listenerProfileRepository);
	}

	@Test
	void resolve_returnsOnlyCanonicalGhostListenerIdentityAndExactListenerAvatar() {
		UUID ghostUserId = UUID.randomUUID();
		UUID standardUserId = UUID.randomUUID();
		UUID alternateMusicianAvatarId = UUID.randomUUID();
		UUID listenerAvatarId = UUID.randomUUID();
		when(identityRepository.findCandidatesByUserIdIn(List.of(ghostUserId, standardUserId)))
				.thenReturn(List.of(
						candidate(
								ghostUserId,
								alternateMusicianAvatarId,
								listenerAvatarId,
								"  ghosthandle  ",
								ListenerVisibilityMode.GHOST
						),
						candidate(
								standardUserId,
								null,
								UUID.randomUUID(),
								"standard",
								ListenerVisibilityMode.STANDARD
						)
				));
		when(mediaAssetService.getDisplayUrlMap(List.of(listenerAvatarId)))
				.thenReturn(Map.of(listenerAvatarId, "https://cdn.example/listener.jpg"));

		Map<UUID, GhostListenerIdentity> result = resolver.resolve(
				Arrays.asList(ghostUserId, standardUserId, ghostUserId, null));

		assertThat(result).containsOnlyKeys(ghostUserId);
		assertThat(result.get(ghostUserId)).isEqualTo(new GhostListenerIdentity(
				ghostUserId,
				"ghosthandle",
				"https://cdn.example/listener.jpg",
				ListenerVisibilityMode.GHOST
		));
		assertThatThrownBy(() -> result.put(
				UUID.randomUUID(),
				new GhostListenerIdentity(UUID.randomUUID(), "x", null, ListenerVisibilityMode.GHOST)
		)).isInstanceOf(UnsupportedOperationException.class);
		verify(mediaAssetService).getDisplayUrlMap(List.of(listenerAvatarId));
		verify(listenerProfileRepository)
				.findAllByUserIdInForVisibilityRead(List.of(ghostUserId, standardUserId));
	}

	@Test
	void resolve_keepsGhostIdentityWhenAvatarLookupFails() {
		UUID userId = UUID.randomUUID();
		UUID listenerAvatarId = UUID.randomUUID();
		when(identityRepository.findCandidatesByUserIdIn(List.of(userId)))
				.thenReturn(List.of(candidate(
						userId,
						null,
						listenerAvatarId,
						"ghosthandle",
						ListenerVisibilityMode.GHOST
				)));
		when(mediaAssetService.getDisplayUrlMap(List.of(listenerAvatarId)))
				.thenThrow(new IllegalStateException("media unavailable"));

		assertThat(resolver.resolve(List.of(userId)).get(userId))
				.extracting(
						GhostListenerIdentity::username,
						GhostListenerIdentity::profilePictureUrl,
						GhostListenerIdentity::visibilityMode
				)
				.containsExactly("ghosthandle", null, ListenerVisibilityMode.GHOST);
	}

	@Test
	void resolve_anonymizesPendingListenerInsteadOfFallingBackToRawIdentity() {
		UUID userId = UUID.randomUUID();
		UUID musicianAvatarId = UUID.randomUUID();
		UUID listenerAvatarId = UUID.randomUUID();
		when(listenerProfileRepository.findUserIdsRequiringVisibilityChoice(List.of(userId)))
				.thenReturn(Set.of(userId));
		when(identityRepository.findCandidatesByUserIdIn(List.of(userId)))
				.thenReturn(List.of(candidate(
						userId,
						musicianAvatarId,
						listenerAvatarId,
						"must-not-leak",
						ListenerVisibilityMode.STANDARD
				)));

		GhostListenerIdentity identity = resolver.resolve(List.of(userId)).get(userId);

		assertThat(identity).isEqualTo(new GhostListenerIdentity(
				userId,
				"Kullanici",
				null,
				ListenerVisibilityMode.GHOST
		));
		verifyNoInteractions(mediaAssetService);
	}

	@Test
	@SuppressWarnings({"rawtypes", "unchecked"})
	void resolve_chunksLargeInputsWithoutNPlusOneQueries() {
		List<UUID> userIds = new ArrayList<>();
		for (int index = 0; index < 101; index++) {
			userIds.add(UUID.randomUUID());
		}
		when(identityRepository.findCandidatesByUserIdIn(anyCollection())).thenReturn(List.of());

		assertThat(resolver.resolve(userIds)).isEmpty();

		ArgumentCaptor<Collection> captor = ArgumentCaptor.forClass(Collection.class);
		verify(identityRepository, times(3)).findCandidatesByUserIdIn(captor.capture());
		verify(listenerProfileRepository, times(3))
				.findAllByUserIdInForVisibilityRead(anyCollection());
		assertThat(captor.getAllValues())
				.extracting(Collection::size)
				.containsExactly(50, 50, 1);
		verifyNoInteractions(mediaAssetService);
	}

	@Test
	void resolve_skipsDependenciesForNullOrEmptyInputs() {
		assertThat(resolver.resolve(null)).isEmpty();
		assertThat(resolver.resolve(List.of())).isEmpty();
		verifyNoInteractions(identityRepository, mediaAssetService, listenerProfileRepository);
	}

	private PersonalProfileAvatarCandidate candidate(
			UUID userId,
			UUID musicianMediaId,
			UUID listenerMediaId,
			String username,
			ListenerVisibilityMode visibilityMode
	) {
		return new PersonalProfileAvatarCandidate(
				userId,
				musicianMediaId,
				listenerMediaId,
				null,
				null,
				username,
				visibilityMode
		);
	}
}
