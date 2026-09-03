package com.berkayb.soundconnect.modules.profile.shared.identity;

import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.repository.ListenerProfileRepository;
import com.berkayb.soundconnect.modules.profile.shared.avatar.PersonalProfileAvatarCandidate;
import com.berkayb.soundconnect.modules.profile.shared.avatar.PersonalProfileAvatarRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Resolves publicly restricted listener identities without per-user lookups.
 *
 * <p>Repository failures deliberately propagate so callers fail closed instead
 * of falling back to a potentially identifying professional name or avatar.
 * Media enrichment is safe to omit: an unavailable avatar becomes {@code null}
 * while the canonical username and ghost marker remain authoritative. A
 * listener whose first choice is pending receives a deliberately anonymous
 * compatibility projection, preventing downstream fallbacks from exposing a
 * raw or alternate-profile identity for pre-existing records.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GhostListenerIdentityBatchResolver {
	static final int BATCH_SIZE = 50;
	private static final String UNKNOWN_USERNAME = "Kullanici";

	private final PersonalProfileAvatarRepository identityRepository;
	private final MediaAssetService mediaAssetService;
	private final ListenerProfileRepository listenerProfileRepository;

	@Transactional
	public Map<UUID, GhostListenerIdentity> resolve(Collection<UUID> userIds) {
		List<UUID> normalizedIds = normalize(userIds);
		if (normalizedIds.isEmpty()) return Map.of();

		Map<UUID, GhostListenerIdentity> resolved = new LinkedHashMap<>();
		for (int offset = 0; offset < normalizedIds.size(); offset += BATCH_SIZE) {
			int end = Math.min(offset + BATCH_SIZE, normalizedIds.size());
			List<UUID> batch = normalizedIds.subList(offset, end);
			// Keep identity projection and the visibility decision in one linearized
			// snapshot. Shared locks permit concurrent readers but serialize with the
			// write lock used by ghost activation.
			listenerProfileRepository.findAllByUserIdInForVisibilityRead(batch);
			Set<UUID> pendingUserIds = listenerProfileRepository
					.findUserIdsRequiringVisibilityChoice(batch);
			List<PersonalProfileAvatarCandidate> candidates =
					identityRepository.findCandidatesByUserIdIn(batch);
			resolveBatch(
					candidates,
					pendingUserIds == null ? Set.of() : pendingUserIds,
					resolved
			);
		}
		return resolved.isEmpty()
				? Map.of()
				: Collections.unmodifiableMap(new LinkedHashMap<>(resolved));
	}

	private void resolveBatch(
			List<PersonalProfileAvatarCandidate> candidates,
			Set<UUID> pendingUserIds,
			Map<UUID, GhostListenerIdentity> resolved
	) {
		if (candidates == null || candidates.isEmpty()) return;

		List<PersonalProfileAvatarCandidate> restricted = candidates.stream()
				.filter(Objects::nonNull)
				.filter(candidate -> candidate.userId() != null)
				.filter(candidate -> pendingUserIds.contains(candidate.userId())
						|| candidate.listenerVisibilityMode() == ListenerVisibilityMode.GHOST)
				.toList();
		if (restricted.isEmpty()) return;

		LinkedHashSet<UUID> avatarIds = new LinkedHashSet<>();
		for (PersonalProfileAvatarCandidate candidate : restricted) {
			if (!pendingUserIds.contains(candidate.userId()) && candidate.listenerMediaId() != null) {
				avatarIds.add(candidate.listenerMediaId());
			}
		}
		Map<UUID, String> avatarUrls = resolveAvatarUrls(avatarIds);

		for (PersonalProfileAvatarCandidate candidate : restricted) {
			boolean pendingChoice = pendingUserIds.contains(candidate.userId());
			String avatarUrl = candidate.listenerMediaId() == null
					? null
					: avatarUrls.get(candidate.listenerMediaId());
			resolved.putIfAbsent(candidate.userId(), new GhostListenerIdentity(
					candidate.userId(),
					pendingChoice ? UNKNOWN_USERNAME : canonicalUsername(candidate.username()),
					pendingChoice ? null : avatarUrl,
					ListenerVisibilityMode.GHOST
			));
		}
	}

	private Map<UUID, String> resolveAvatarUrls(Collection<UUID> avatarIds) {
		if (avatarIds.isEmpty()) return Map.of();
		try {
			Map<UUID, String> urls = mediaAssetService.getDisplayUrlMap(List.copyOf(avatarIds));
			return urls == null ? Map.of() : urls;
		} catch (RuntimeException exception) {
			log.warn("Could not resolve ghost listener avatars; identities remain available without images", exception);
			return Map.of();
		}
	}

	private List<UUID> normalize(Collection<UUID> userIds) {
		if (userIds == null || userIds.isEmpty()) return List.of();
		LinkedHashSet<UUID> distinct = new LinkedHashSet<>();
		for (UUID userId : userIds) {
			if (userId != null) distinct.add(userId);
		}
		return distinct.isEmpty() ? List.of() : new ArrayList<>(distinct);
	}

	private String canonicalUsername(String username) {
		return username == null || username.isBlank() ? UNKNOWN_USERNAME : username.trim();
	}
}
