package com.berkayb.soundconnect.modules.profile.shared.identity;

import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;

import java.util.UUID;

/**
 * Safe contextual identity for a listener whose public showcase is hidden.
 * The username and avatar are deliberately listener-owned; no alternate profile
 * name or avatar may be substituted for a ghost listener. Pending first-choice
 * accounts use an anonymous compatibility value with the same restricted marker
 * so old records cannot fall through to raw identity fields.
 */
public record GhostListenerIdentity(
		UUID userId,
		String username,
		String profilePictureUrl,
		ListenerVisibilityMode visibilityMode
) {
	public GhostListenerIdentity {
		if (visibilityMode != ListenerVisibilityMode.GHOST) {
			throw new IllegalArgumentException("Ghost listener identity requires GHOST visibility");
		}
	}
}
