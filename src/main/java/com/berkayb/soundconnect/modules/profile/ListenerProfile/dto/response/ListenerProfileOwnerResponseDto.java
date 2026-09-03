package com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.response;

import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/**
 * Listener self-view. Pending onboarding and ghost mode intentionally omit
 * showcase and social-graph fields even for the owner while retaining the
 * stable identity and avatar.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ListenerProfileOwnerResponseDto(
		UUID id,
		UUID userId,
		String username,
		ListenerVisibilityMode visibilityMode,
		boolean visibilityChoiceCompleted,
		long version,
		Instant visibilityChangedAt,
		String bio,
		UUID profilePictureMediaId,
		String profilePictureUrl,
		Long followerCount,
		Long followingCount,
		boolean profileContentVisible,
		boolean profileContentEditable,
		boolean avatarEditable,
		boolean canReceiveFollowers
) {
}
