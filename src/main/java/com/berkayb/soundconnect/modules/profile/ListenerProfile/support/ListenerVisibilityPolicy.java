package com.berkayb.soundconnect.modules.profile.ListenerProfile.support;

import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.entity.ListenerProfile;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.repository.ListenerProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.List;

/**
 * Single source of truth for cross-module listener visibility decisions.
 *
 * <p>Mutations that race with a visibility transition must use
 * {@link #lockAndIsPubliclyRestricted(UUID)} from an existing transaction. Both
 * the follow writer and visibility transition then contend on the same
 * listener-profile row, preventing a follower from being inserted after a
 * restricted transition has removed the incoming graph.</p>
 */
@Component
@RequiredArgsConstructor
public class ListenerVisibilityPolicy {

	private final ListenerProfileRepository listenerProfileRepository;

	public boolean isGhost(UUID userId) {
		return listenerProfileRepository.existsByUserIdAndVisibilityMode(
				userId,
				ListenerVisibilityMode.GHOST
		);
	}

	public boolean isGhostProfile(UUID listenerProfileId) {
		return listenerProfileRepository.existsByIdAndVisibilityMode(
				listenerProfileId,
				ListenerVisibilityMode.GHOST
		);
	}

	/**
	 * Returns both public restriction reasons under one deterministic shared-lock
	 * batch. Pending listeners are omitted entirely; completed ghost listeners
	 * retain their intentionally limited public identity.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public PublicVisibilityRestrictions publicVisibilityRestrictions(Collection<UUID> userIds) {
		if (userIds == null || userIds.isEmpty()) return PublicVisibilityRestrictions.empty();
		Set<UUID> normalizedIds = new LinkedHashSet<>();
		for (UUID userId : userIds) {
			if (userId != null) normalizedIds.add(userId);
		}
		if (normalizedIds.isEmpty()) return PublicVisibilityRestrictions.empty();

		List<ListenerProfile> lockedProfiles = listenerProfileRepository
				.findAllByUserIdInForVisibilityRead(normalizedIds);
		if (lockedProfiles == null || lockedProfiles.isEmpty()) {
			return PublicVisibilityRestrictions.empty();
		}

		Set<UUID> ghostIds = new LinkedHashSet<>();
		Set<UUID> pendingChoiceIds = new LinkedHashSet<>();
		for (ListenerProfile profile : lockedProfiles) {
			if (profile == null || profile.getUser() == null || profile.getUser().getId() == null) {
				continue;
			}
			UUID userId = profile.getUser().getId();
			if (!profile.isVisibilityChoiceCompleted()) {
				pendingChoiceIds.add(userId);
			} else if (profile.isGhost()) {
				ghostIds.add(userId);
			}
		}
		return new PublicVisibilityRestrictions(ghostIds, pendingChoiceIds);
	}

	/**
	 * Locks every existing listener row represented by the supplied users in a
	 * deterministic order. This is used when a cross-type result set must not
	 * expose a corrupt secondary personal profile during a concurrent ghost
	 * transition.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public Set<UUID> ghostUserIds(Collection<UUID> userIds) {
		if (userIds == null || userIds.isEmpty()) return Set.of();
		Set<UUID> normalizedIds = new LinkedHashSet<>();
		for (UUID userId : userIds) {
			if (userId != null) normalizedIds.add(userId);
		}
		if (normalizedIds.isEmpty()) return Set.of();

		List<ListenerProfile> lockedProfiles = listenerProfileRepository
				.findAllByUserIdInForVisibilityRead(normalizedIds);
		if (lockedProfiles == null || lockedProfiles.isEmpty()) return Set.of();
		Set<UUID> ghostIds = new LinkedHashSet<>();
		for (ListenerProfile profile : lockedProfiles) {
			if (profile != null && profile.getVisibilityMode() == ListenerVisibilityMode.GHOST
					&& profile.getUser() != null && profile.getUser().getId() != null) {
				ghostIds.add(profile.getUser().getId());
			}
		}
		return ghostIds.isEmpty() ? Set.of() : Set.copyOf(ghostIds);
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public boolean lockAndIsGhostProfile(UUID listenerProfileId) {
		return listenerProfileRepository.findByIdForUpdate(listenerProfileId)
		                                .map(profile -> profile.getVisibilityMode() == ListenerVisibilityMode.GHOST)
		                                .orElse(false);
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public boolean lockAndIsGhost(UUID userId) {
		return listenerProfileRepository.findByUserIdForUpdate(userId)
		                                .map(profile -> profile.getVisibilityMode() == ListenerVisibilityMode.GHOST)
		                                .orElse(false);
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public boolean lockAndIsPubliclyRestrictedProfile(UUID listenerProfileId) {
		return listenerProfileRepository.findByIdForUpdate(listenerProfileId)
		                                .map(ListenerProfile::isPubliclyRestricted)
		                                .orElse(false);
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public boolean lockAndIsPubliclyRestricted(UUID userId) {
		return listenerProfileRepository.findByUserIdForUpdate(userId)
		                                .map(ListenerProfile::isPubliclyRestricted)
		                                .orElse(false);
	}

	/**
	 * Holds a shared visibility lock for the rest of the caller transaction.
	 * Public reads use this rather than an exclusive lock so readers can proceed
	 * concurrently while still serializing with a visibility transition.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public boolean lockForReadAndIsGhostProfile(UUID listenerProfileId) {
		return listenerProfileRepository.findByIdForVisibilityRead(listenerProfileId)
		                                .map(profile -> profile.getVisibilityMode() == ListenerVisibilityMode.GHOST)
		                                .orElse(false);
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public boolean lockForReadAndIsGhost(UUID userId) {
		return listenerProfileRepository.findByUserIdForVisibilityRead(userId)
		                                .map(profile -> profile.getVisibilityMode() == ListenerVisibilityMode.GHOST)
		                                .orElse(false);
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public boolean lockForReadAndIsPubliclyRestrictedProfile(UUID listenerProfileId) {
		return listenerProfileRepository.findByIdForVisibilityRead(listenerProfileId)
		                                .map(ListenerProfile::isPubliclyRestricted)
		                                .orElse(false);
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public boolean lockForReadAndIsPubliclyRestricted(UUID userId) {
		return listenerProfileRepository.findByUserIdForVisibilityRead(userId)
		                                .map(ListenerProfile::isPubliclyRestricted)
		                                .orElse(false);
	}

	public record PublicVisibilityRestrictions(
			Set<UUID> ghostUserIds,
			Set<UUID> pendingChoiceUserIds
	) {
		public PublicVisibilityRestrictions {
			ghostUserIds = ghostUserIds == null ? Set.of() : Set.copyOf(ghostUserIds);
			pendingChoiceUserIds = pendingChoiceUserIds == null
					? Set.of()
					: Set.copyOf(pendingChoiceUserIds);
		}

		public static PublicVisibilityRestrictions empty() {
			return new PublicVisibilityRestrictions(Set.of(), Set.of());
		}
	}
}
