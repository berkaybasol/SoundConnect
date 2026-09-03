package com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.Objects;
import java.util.UUID;

/**
 * Dedicated avatar command. A {@code null} media id intentionally removes the
 * current avatar; profile-content updates never interpret it as an avatar edit.
 * The presence bit distinguishes that explicit removal from an accidental empty
 * JSON object. {@code expectedVersion} makes delayed durable-upload retries a
 * compare-and-set operation so an old upload cannot overwrite a newer choice.
 */
public final class ListenerAvatarUpdateRequestDto {
	private UUID profilePictureMediaId;
	private boolean profilePictureMediaIdProvided;
	@NotNull
	@PositiveOrZero
	private Long expectedVersion;

	public ListenerAvatarUpdateRequestDto() {
	}

	public ListenerAvatarUpdateRequestDto(UUID profilePictureMediaId, Long expectedVersion) {
		this.profilePictureMediaId = profilePictureMediaId;
		this.profilePictureMediaIdProvided = true;
		this.expectedVersion = expectedVersion;
	}

	@JsonProperty("profilePictureMediaId")
	public UUID profilePictureMediaId() {
		return profilePictureMediaId;
	}

	@JsonSetter("profilePictureMediaId")
	public void setProfilePictureMediaId(UUID profilePictureMediaId) {
		this.profilePictureMediaId = profilePictureMediaId;
		this.profilePictureMediaIdProvided = true;
	}

	@JsonProperty("expectedVersion")
	public Long expectedVersion() {
		return expectedVersion;
	}

	@JsonSetter("expectedVersion")
	public void setExpectedVersion(Long expectedVersion) {
		this.expectedVersion = expectedVersion;
	}

	@JsonIgnore
	@AssertTrue(message = "profilePictureMediaId must be present; use null to remove the avatar")
	public boolean isProfilePictureMediaIdProvided() {
		return profilePictureMediaIdProvided;
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) return true;
		if (!(other instanceof ListenerAvatarUpdateRequestDto that)) return false;
		return profilePictureMediaIdProvided == that.profilePictureMediaIdProvided
				&& Objects.equals(profilePictureMediaId, that.profilePictureMediaId)
				&& Objects.equals(expectedVersion, that.expectedVersion);
	}

	@Override
	public int hashCode() {
		return Objects.hash(profilePictureMediaId, profilePictureMediaIdProvided, expectedVersion);
	}
}
