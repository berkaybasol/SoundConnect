package com.berkayb.soundconnect.modules.profile.ListenerProfile.service;

import com.berkayb.soundconnect.modules.profile.ListenerProfile.entity.ListenerProfile;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.entity.ListenerSpotifyPlaylist;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.repository.ListenerProfileRepository;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.repository.ListenerSpotifyPlaylistRepository;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.support.ListenerProfileProvisioner;
import com.berkayb.soundconnect.modules.spotify.dto.response.SpotifyPlaylistMetadataDto;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Transaction boundary for playlist mutations. Spotify network traffic is
 * deliberately resolved by the outer service before entering this class, so a
 * slow upstream never occupies a listener row lock or JDBC connection.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ListenerPlaylistMutationService {

	private final ListenerProfileRepository listenerProfileRepository;
	private final ListenerSpotifyPlaylistRepository playlistRepository;
	private final UserEntityFinder userEntityFinder;
	private final ListenerProfileProvisioner listenerProfileProvisioner;

	/**
	 * Rejects stale/restricted requests before Spotify is called and recognizes
	 * exact response-lost retries without consuming upstream capacity. Existing
	 * server-owned snapshots are returned so reorder/removal operations do not
	 * repeat oEmbed calls.
	 */
	@Transactional
	public ReplacementPlan prepareReplacement(
			UUID userId,
			List<String> desiredPlaylistIds,
			long expectedVersion
	) {
		ListenerProfile profile = findOrProvisionForUpdate(userId);
		assertContentEditable(profile);
		List<ListenerSpotifyPlaylist> current = playlistRepository
				.findAllByListenerProfileIdOrderByPositionAsc(profile.getId());
		List<String> currentIds = current.stream()
				.map(ListenerSpotifyPlaylist::getSpotifyPlaylistId)
				.toList();
		if (currentIds.equals(desiredPlaylistIds)) {
			return ReplacementPlan.exactRetry();
		}
		assertExpectedVersion(profile, expectedVersion);
		return ReplacementPlan.required(current.stream()
				.map(this::toMetadataSnapshot)
				.toList());
	}

	@Transactional
	public void replace(
			UUID userId,
			List<SpotifyPlaylistMetadataDto> metadata,
			long expectedVersion
	) {
		ListenerProfile profile = findOrProvisionForUpdate(userId);
		assertContentEditable(profile);
		List<String> desiredIds = metadata.stream()
				.map(SpotifyPlaylistMetadataDto::spotifyPlaylistId)
				.toList();
		List<String> currentIds = playlistRepository
				.findSpotifyPlaylistIdsByListenerProfileId(profile.getId());
		if (currentIds.equals(desiredIds)) {
			return;
		}
		assertExpectedVersion(profile, expectedVersion);

		playlistRepository.deleteAllByListenerProfileId(profile.getId());
		playlistRepository.flush();

		List<ListenerSpotifyPlaylist> replacements = java.util.stream.IntStream
				.range(0, metadata.size())
				.mapToObj(position -> ListenerSpotifyPlaylist.create(
						profile,
						metadata.get(position),
						position
				))
				.toList();

		profile.recordPlaylistMutation();
		playlistRepository.saveAll(replacements);
		ListenerProfile updated = listenerProfileRepository.saveAndFlush(profile);
		log.info(
				"Listener playlists replaced. userId={} profileId={} playlistCount={} version={}",
				userId,
				profile.getId(),
				replacements.size(),
				updated.getVersion()
		);
	}

	private ListenerProfile findOrProvisionForUpdate(UUID userId) {
		userEntityFinder.getUser(userId);
		return listenerProfileRepository.findByUserIdForUpdate(userId)
				.orElseGet(() -> listenerProfileProvisioner.ensureExistsForUpdate(userId));
	}

	private void assertContentEditable(ListenerProfile profile) {
		if (profile.isPubliclyRestricted()) {
			throw new SoundConnectException(ErrorType.LISTENER_PROFILE_CONTENT_LOCKED);
		}
	}

	private void assertExpectedVersion(ListenerProfile profile, long expectedVersion) {
		if (profile.getVersion() != expectedVersion) {
			throw new SoundConnectException(ErrorType.LISTENER_PROFILE_VERSION_CONFLICT);
		}
	}

	private SpotifyPlaylistMetadataDto toMetadataSnapshot(ListenerSpotifyPlaylist playlist) {
		return new SpotifyPlaylistMetadataDto(
				playlist.getSpotifyPlaylistId(),
				playlist.getTitle(),
				playlist.getCoverImageUrl(),
				playlist.getSpotifyUrl()
		);
	}

	public record ReplacementPlan(
			boolean replacementRequired,
			List<SpotifyPlaylistMetadataDto> reusableMetadata
	) {
		public ReplacementPlan {
			reusableMetadata = reusableMetadata == null ? List.of() : List.copyOf(reusableMetadata);
		}

		private static ReplacementPlan exactRetry() {
			return new ReplacementPlan(false, List.of());
		}

		private static ReplacementPlan required(List<SpotifyPlaylistMetadataDto> reusableMetadata) {
			return new ReplacementPlan(true, reusableMetadata);
		}
	}
}
