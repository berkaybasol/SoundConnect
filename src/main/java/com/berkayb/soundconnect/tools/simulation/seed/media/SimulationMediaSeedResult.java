package com.berkayb.soundconnect.tools.simulation.seed.media;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable identities created by one media seed run.
 *
 * <p>{@code avatarAssetIds} contains both account and band logical keys. Band
 * avatars are already attached by the media slice; the bootstrapper must pass
 * only manifest account keys to the separate account-profile picture phase.</p>
 */
public record SimulationMediaSeedResult(
		Map<String, UUID> avatarAssetIds,
		List<UUID> createdTrackIds,
		List<UUID> profilePublicationIds,
		List<MediaTarget> profileMediaTargets,
		List<MediaTarget> trackMediaTargets
) {
	public SimulationMediaSeedResult {
		avatarAssetIds = immutableAssetMap(avatarAssetIds);
		createdTrackIds = createdTrackIds == null ? List.of() : List.copyOf(createdTrackIds);
		profilePublicationIds = profilePublicationIds == null
				? List.of()
				: List.copyOf(profilePublicationIds);
		profileMediaTargets = profileMediaTargets == null ? List.of() : List.copyOf(profileMediaTargets);
		trackMediaTargets = trackMediaTargets == null ? List.of() : List.copyOf(trackMediaTargets);

		if (!profilePublicationIds.equals(
				profileMediaTargets.stream().map(MediaTarget::publicationId).toList())) {
			throw new IllegalArgumentException(
					"Profile publication ids must match profile-media targets in order");
		}
		if (!createdTrackIds.equals(
				trackMediaTargets.stream().map(MediaTarget::publicationId).toList())) {
			throw new IllegalArgumentException(
					"Track ids must match track-media targets in order");
		}
		assertUnique("createdTrackIds", createdTrackIds);
		assertUnique("profilePublicationIds", profilePublicationIds);
		assertUniqueTargets("profileMediaTargets", profileMediaTargets);
		assertUniqueTargets("trackMediaTargets", trackMediaTargets);
		Set<UUID> everyAsset = new LinkedHashSet<>(avatarAssetIds.values());
		profileMediaTargets.forEach(target -> addUniqueAsset(everyAsset, target.assetId()));
		trackMediaTargets.forEach(target -> addUniqueAsset(everyAsset, target.assetId()));
	}

	private static Map<String, UUID> immutableAssetMap(Map<String, UUID> source) {
		if (source == null) return Map.of();
		Map<String, UUID> copy = new LinkedHashMap<>();
		Set<UUID> assetIds = new LinkedHashSet<>();
		for (Map.Entry<String, UUID> entry : source.entrySet()) {
			if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) {
				throw new IllegalArgumentException("Avatar asset identity must be complete");
			}
			if (copy.putIfAbsent(entry.getKey(), entry.getValue()) != null
					|| !assetIds.add(entry.getValue())) {
				throw new IllegalArgumentException("Avatar asset identities must be unique");
			}
		}
		return Collections.unmodifiableMap(copy);
	}

	private static void assertUnique(String field, List<UUID> ids) {
		if (ids.stream().anyMatch(Objects::isNull)
				|| new LinkedHashSet<>(ids).size() != ids.size()) {
			throw new IllegalArgumentException(field + " must contain unique non-null ids");
		}
	}

	private static void assertUniqueTargets(String field, List<MediaTarget> targets) {
		Set<String> logicalKeys = new LinkedHashSet<>();
		Set<UUID> assetIds = new LinkedHashSet<>();
		Set<UUID> publicationIds = new LinkedHashSet<>();
		for (MediaTarget target : targets) {
			if (target == null
					|| !logicalKeys.add(target.logicalKey())
					|| !assetIds.add(target.assetId())
					|| !publicationIds.add(target.publicationId())) {
				throw new IllegalArgumentException(field + " identities must be complete and unique");
			}
		}
	}

	private static void addUniqueAsset(Set<UUID> assets, UUID assetId) {
		if (!assets.add(assetId)) {
			throw new IllegalArgumentException("Media assets must be unique across seed purposes");
		}
	}

	/** MEDIA engagement targets for profile-media cards are MediaAsset ids. */
	public List<UUID> profileMediaAssetIds() {
		return profileMediaTargets.stream().map(MediaTarget::assetId).toList();
	}

	/** MEDIA engagement targets for Track cards are their backing MediaAsset ids. */
	public List<UUID> trackMediaAssetIds() {
		return trackMediaTargets.stream().map(MediaTarget::assetId).toList();
	}

	public record MediaTarget(
			String logicalKey,
			String ownerKey,
			UUID assetId,
			UUID publicationId
	) {
		public MediaTarget {
			if (logicalKey == null || logicalKey.isBlank()
					|| ownerKey == null || ownerKey.isBlank()
					|| assetId == null || publicationId == null) {
				throw new IllegalArgumentException("Media target identity must be complete");
			}
		}
	}
}
