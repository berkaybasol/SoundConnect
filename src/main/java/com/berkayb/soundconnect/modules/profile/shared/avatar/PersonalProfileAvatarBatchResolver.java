package com.berkayb.soundconnect.modules.profile.shared.avatar;

import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Resolves personal avatars with two bounded operations: one profile projection
 * query and one public/READY media URL query. Priority is deterministic and
 * personal-only: musician, listener, organizer, then producer.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PersonalProfileAvatarBatchResolver {
	private static final int MAX_BATCH_SIZE = 50;

	private final PersonalProfileAvatarRepository avatarRepository;
	private final MediaAssetService mediaAssetService;

	public Map<UUID, String> resolve(Collection<UUID> userIds) {
		LinkedHashSet<UUID> distinctUserIds = new LinkedHashSet<>();
		if (userIds != null) {
			userIds.stream().filter(Objects::nonNull).forEach(distinctUserIds::add);
		}
		if (distinctUserIds.isEmpty()) {
			return Map.of();
		}
		if (distinctUserIds.size() > MAX_BATCH_SIZE) {
			throw new IllegalArgumentException("Personal avatar batch size exceeds " + MAX_BATCH_SIZE);
		}

		List<PersonalProfileAvatarCandidate> candidates;
		try {
			candidates = avatarRepository.findCandidatesByUserIdIn(distinctUserIds);
		} catch (RuntimeException exception) {
			// Avatar enrichment must never make the core active-table feed unavailable.
			log.warn("Could not batch-read personal profile avatar candidates", exception);
			return Map.of();
		}
		if (candidates == null || candidates.isEmpty()) {
			return Map.of();
		}
		LinkedHashSet<UUID> mediaIds = new LinkedHashSet<>();
		for (PersonalProfileAvatarCandidate candidate : candidates) {
			if (candidate == null) continue;
			addIfPresent(mediaIds, candidate.musicianMediaId());
			addIfPresent(mediaIds, candidate.listenerMediaId());
			addIfPresent(mediaIds, candidate.organizerMediaId());
			addIfPresent(mediaIds, candidate.producerMediaId());
		}
		if (mediaIds.isEmpty()) {
			return Map.of();
		}

		Map<UUID, String> displayUrls;
		try {
			displayUrls = mediaAssetService.getDisplayUrlMap(List.copyOf(mediaIds));
		} catch (RuntimeException exception) {
			log.warn("Could not batch-enrich personal profile avatars", exception);
			return Map.of();
		}
		if (displayUrls == null || displayUrls.isEmpty()) {
			return Map.of();
		}

		Map<UUID, String> resolved = new LinkedHashMap<>();
		for (PersonalProfileAvatarCandidate candidate : candidates) {
			if (candidate == null || candidate.userId() == null) continue;
			String url = firstDisplayableUrl(candidate, displayUrls);
			if (url != null) {
				resolved.putIfAbsent(candidate.userId(), url);
			}
		}
		return Map.copyOf(resolved);
	}

	private void addIfPresent(Collection<UUID> mediaIds, UUID mediaId) {
		if (mediaId != null) mediaIds.add(mediaId);
	}

	private String firstDisplayableUrl(
			PersonalProfileAvatarCandidate candidate,
			Map<UUID, String> displayUrls
	) {
		for (UUID mediaId : new UUID[]{
				candidate.musicianMediaId(),
				candidate.listenerMediaId(),
				candidate.organizerMediaId(),
				candidate.producerMediaId()
		}) {
			String url = mediaId == null ? null : displayUrls.get(mediaId);
			if (url != null && !url.isBlank()) return url;
		}
		return null;
	}
}
