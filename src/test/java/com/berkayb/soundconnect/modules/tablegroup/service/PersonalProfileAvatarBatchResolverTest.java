package com.berkayb.soundconnect.modules.tablegroup.service;

import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.profile.shared.avatar.PersonalProfileAvatarBatchResolver;
import com.berkayb.soundconnect.modules.profile.shared.avatar.PersonalProfileAvatarCandidate;
import com.berkayb.soundconnect.modules.profile.shared.avatar.PersonalProfileAvatarRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PersonalProfileAvatarBatchResolverTest {

	@Mock
	private PersonalProfileAvatarRepository avatarRepository;

	@Mock
	private MediaAssetService mediaAssetService;

	private PersonalProfileAvatarBatchResolver resolver;

	@BeforeEach
	void setUp() {
		resolver = new PersonalProfileAvatarBatchResolver(avatarRepository, mediaAssetService);
	}

	@Test
	void resolve_shouldBatchAllPersonalTypesAndFallThroughUnavailableHigherPriorityAsset() {
		UUID musicianUserId = UUID.randomUUID();
		UUID fallbackUserId = UUID.randomUUID();
		UUID organizerUserId = UUID.randomUUID();
		UUID producerUserId = UUID.randomUUID();
		UUID noAvatarUserId = UUID.randomUUID();
		UUID musicianMediaId = UUID.randomUUID();
		UUID unavailableMusicianMediaId = UUID.randomUUID();
		UUID listenerMediaId = UUID.randomUUID();
		UUID organizerMediaId = UUID.randomUUID();
		UUID producerMediaId = UUID.randomUUID();
		Set<UUID> userIds = new LinkedHashSet<>(List.of(
				musicianUserId, fallbackUserId, organizerUserId, producerUserId, noAvatarUserId));
		List<PersonalProfileAvatarCandidate> candidates = List.of(
				candidate(musicianUserId, musicianMediaId, null, null, null),
				candidate(fallbackUserId, unavailableMusicianMediaId, listenerMediaId, null, null),
				candidate(organizerUserId, null, null, organizerMediaId, null),
				candidate(producerUserId, null, null, null, producerMediaId),
				candidate(noAvatarUserId, null, null, null, null)
		);
		when(avatarRepository.findCandidatesByUserIdIn(userIds)).thenReturn(candidates);
		when(mediaAssetService.getDisplayUrlMap(List.of(
				musicianMediaId,
				unavailableMusicianMediaId,
				listenerMediaId,
				organizerMediaId,
				producerMediaId
		))).thenReturn(Map.of(
				musicianMediaId, "https://cdn.example/musician.jpg",
				listenerMediaId, "https://cdn.example/listener.jpg",
				organizerMediaId, "https://cdn.example/organizer.jpg",
				producerMediaId, "https://cdn.example/producer.jpg"
		));

		Map<UUID, String> result = resolver.resolve(userIds);

		assertThat(result).containsExactlyInAnyOrderEntriesOf(Map.of(
				musicianUserId, "https://cdn.example/musician.jpg",
				fallbackUserId, "https://cdn.example/listener.jpg",
				organizerUserId, "https://cdn.example/organizer.jpg",
				producerUserId, "https://cdn.example/producer.jpg"
		));
		assertThat(result).doesNotContainKey(noAvatarUserId);
		verify(avatarRepository).findCandidatesByUserIdIn(userIds);
		verify(mediaAssetService).getDisplayUrlMap(List.of(
				musicianMediaId,
				unavailableMusicianMediaId,
				listenerMediaId,
				organizerMediaId,
				producerMediaId
		));
	}

	@Test
	void resolve_shouldAcceptExactlyFiftyOwnersInOneProjectionQuery() {
		List<UUID> userIds = new ArrayList<>();
		for (int index = 0; index < 50; index++) {
			userIds.add(UUID.randomUUID());
		}
		when(avatarRepository.findCandidatesByUserIdIn(new LinkedHashSet<>(userIds)))
				.thenReturn(List.of());

		assertThat(resolver.resolve(userIds)).isEmpty();

		verify(avatarRepository).findCandidatesByUserIdIn(new LinkedHashSet<>(userIds));
		verifyNoInteractions(mediaAssetService);
	}

	@Test
	void resolve_shouldRejectMoreThanFiftyDistinctOwnersBeforeAnyQuery() {
		List<UUID> userIds = new ArrayList<>();
		for (int index = 0; index < 51; index++) {
			userIds.add(UUID.randomUUID());
		}

		assertThatThrownBy(() -> resolver.resolve(userIds))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("50");
		verifyNoInteractions(avatarRepository, mediaAssetService);
	}

	@Test
	void resolve_shouldFailOpenWhenProfileProjectionQueryFails() {
		UUID userId = UUID.randomUUID();
		when(avatarRepository.findCandidatesByUserIdIn(Set.of(userId)))
				.thenThrow(new IllegalStateException("profile read unavailable"));

		assertThat(resolver.resolve(Set.of(userId))).isEmpty();

		verifyNoInteractions(mediaAssetService);
	}

	@Test
	void resolve_shouldTreatNullProjectionResultAsNoAvatar() {
		UUID userId = UUID.randomUUID();
		when(avatarRepository.findCandidatesByUserIdIn(Set.of(userId))).thenReturn(null);

		assertThat(resolver.resolve(Set.of(userId))).isEmpty();

		verifyNoInteractions(mediaAssetService);
	}

	@Test
	void resolve_shouldFailOpenWhenMediaDisplayUrlLookupFails() {
		UUID userId = UUID.randomUUID();
		UUID mediaId = UUID.randomUUID();
		when(avatarRepository.findCandidatesByUserIdIn(Set.of(userId)))
				.thenReturn(List.of(candidate(userId, mediaId, null, null, null)));
		when(mediaAssetService.getDisplayUrlMap(List.of(mediaId)))
				.thenThrow(new IllegalStateException("media read unavailable"));

		assertThat(resolver.resolve(Set.of(userId))).isEmpty();
	}

	@Test
	void resolve_shouldSkipQueriesForEmptyInput() {
		assertThat(resolver.resolve(null)).isEmpty();
		assertThat(resolver.resolve(List.of())).isEmpty();
		verifyNoInteractions(avatarRepository, mediaAssetService);
	}

	private PersonalProfileAvatarCandidate candidate(
			UUID userId,
			UUID musicianMediaId,
			UUID listenerMediaId,
			UUID organizerMediaId,
			UUID producerMediaId
	) {
		return new PersonalProfileAvatarCandidate(
				userId,
				musicianMediaId,
				listenerMediaId,
				organizerMediaId,
				producerMediaId
		);
	}
}
