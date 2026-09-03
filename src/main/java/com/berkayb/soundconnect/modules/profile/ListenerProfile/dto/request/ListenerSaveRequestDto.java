package com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.request;

import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Listener profile creation/content command. Creation may assign the supplied
 * avatar. Update flows retain the avatar field only for legacy wire
 * compatibility: {@code null} or the already-current id is accepted, while an
 * actual avatar change must use {@link ListenerAvatarUpdateRequestDto} with an
 * expected version.
 */
public record ListenerSaveRequestDto(
		@Size(max = DESCRIPTION_MAX_LENGTH) String description,
		UUID profilePictureMediaId
) {
	public static final int DESCRIPTION_MAX_LENGTH = 1024;
}
