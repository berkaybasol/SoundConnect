package com.berkayb.soundconnect.modules.profile.ListenerProfile.service;

import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.request.ListenerPlaylistsUpdateRequestDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.response.ListenerProfileOwnerResponseDto;
import com.berkayb.soundconnect.modules.spotify.dto.response.SpotifyPlaylistMetadataDto;
import com.berkayb.soundconnect.modules.spotify.service.SpotifyService;
import com.berkayb.soundconnect.modules.spotify.support.SpotifyPlaylistMetadataPolicy;
import com.berkayb.soundconnect.modules.spotify.support.SpotifyPlaylistUrlParser;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ListenerPlaylistServiceImpl implements ListenerPlaylistService {

	private final SpotifyService spotifyService;
	private final ListenerPlaylistMutationService mutationService;
	private final ListenerProfileService listenerProfileService;

	@Override
	public ListenerProfileOwnerResponseDto replacePlaylists(
			UUID userId,
			ListenerPlaylistsUpdateRequestDto request
	) {
		validateCommand(userId, request);

		var references = request.spotifyUrls().stream()
				.map(SpotifyPlaylistUrlParser::parse)
				.toList();
		Set<String> uniqueIds = new HashSet<>();
		for (var reference : references) {
			if (!uniqueIds.add(reference.playlistId())) {
				throw new SoundConnectException(ErrorType.LISTENER_PLAYLIST_DUPLICATE);
			}
		}

		List<String> desiredIds = references.stream()
				.map(SpotifyPlaylistUrlParser.SpotifyPlaylistReference::playlistId)
				.toList();
		var replacementPlan = mutationService.prepareReplacement(
				userId,
				desiredIds,
				request.expectedVersion());
		if (!replacementPlan.replacementRequired()) {
			return listenerProfileService.getMyProfile(userId);
		}

		Map<String, SpotifyPlaylistMetadataDto> metadataById = new HashMap<>();
		for (SpotifyPlaylistMetadataDto reusable : replacementPlan.reusableMetadata()) {
			if (reusable == null || reusable.spotifyPlaylistId() == null
					|| metadataById.put(reusable.spotifyPlaylistId(), reusable) != null) {
				throw new SoundConnectException(ErrorType.SPOTIFY_PLAYLIST_METADATA_INVALID);
			}
		}
		var missingReferences = references.stream()
				.filter(reference -> !metadataById.containsKey(reference.playlistId()))
				.toList();
		List<SpotifyPlaylistMetadataDto> hydrated = missingReferences.isEmpty()
				? List.of()
				: spotifyService.getPlaylistMetadataBatch(missingReferences.stream()
						.map(SpotifyPlaylistUrlParser.SpotifyPlaylistReference::canonicalUrl)
						.toList());
		List<SpotifyPlaylistMetadataDto> validatedHydrated =
				validateAuthoritativeMetadata(missingReferences, hydrated);
		for (SpotifyPlaylistMetadataDto snapshot : validatedHydrated) {
			metadataById.put(snapshot.spotifyPlaylistId(), snapshot);
		}

		List<SpotifyPlaylistMetadataDto> metadata = validateAuthoritativeMetadata(
				references,
				references.stream()
						.map(reference -> metadataById.get(reference.playlistId()))
						.toList()
		);

		mutationService.replace(userId, metadata, request.expectedVersion());
		return listenerProfileService.getMyProfile(userId);
	}

	private void validateCommand(UUID userId, ListenerPlaylistsUpdateRequestDto request) {
		if (userId == null || request == null || request.spotifyUrls() == null
				|| request.spotifyUrls().size() > ListenerPlaylistsUpdateRequestDto.MAX_PLAYLISTS
				|| request.expectedVersion() == null || request.expectedVersion() < 0
				|| request.spotifyUrls().stream().anyMatch(url ->
						url == null || url.isBlank() || url.length() > 2048)) {
			throw new SoundConnectException(ErrorType.VALIDATION_ERROR);
		}
	}

	private List<SpotifyPlaylistMetadataDto> validateAuthoritativeMetadata(
			List<SpotifyPlaylistUrlParser.SpotifyPlaylistReference> references,
			List<SpotifyPlaylistMetadataDto> metadata
	) {
		if (metadata == null || metadata.size() != references.size()) {
			throw new SoundConnectException(ErrorType.SPOTIFY_PLAYLIST_METADATA_INVALID);
		}
		var validated = new java.util.ArrayList<SpotifyPlaylistMetadataDto>(metadata.size());
		for (int index = 0; index < references.size(); index++) {
			var reference = references.get(index);
			var snapshot = metadata.get(index);
			SpotifyPlaylistMetadataDto normalized =
					SpotifyPlaylistMetadataPolicy.validateAndNormalize(snapshot);
			if (!reference.playlistId().equals(normalized.spotifyPlaylistId())
					|| !reference.canonicalUrl().equals(normalized.spotifyUrl())) {
				throw new SoundConnectException(ErrorType.SPOTIFY_PLAYLIST_METADATA_INVALID);
			}
			validated.add(normalized);
		}
		return List.copyOf(validated);
	}
}
