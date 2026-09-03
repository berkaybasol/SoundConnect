package com.berkayb.soundconnect.modules.profile.ListenerProfile.entity;

import com.berkayb.soundconnect.modules.profile.shared.BaseProfile;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

@Data
@SuperBuilder
@NoArgsConstructor
@Entity
@Table(name = "tbl_listener-profile")
public class ListenerProfile extends BaseProfile {
	@Builder.Default
	@Enumerated(EnumType.STRING)
	@Column(name = "visibility_mode", nullable = false, length = 16)
	private ListenerVisibilityMode visibilityMode = ListenerVisibilityMode.STANDARD;

	@Column(name = "visibility_changed_at")
	private LocalDateTime visibilityChangedAt;

	/**
	 * Records an explicit onboarding choice independently from the selected mode.
	 * New profiles begin in STANDARD for safe rendering, but that default must not
	 * be mistaken for the listener actively choosing the social profile.
	 */
	@Builder.Default
	@Column(name = "visibility_choice_completed", nullable = false)
	private boolean visibilityChoiceCompleted = false;

	@Version
	@Column(nullable = false)
	private long version;

	@PrePersist
	void applyVisibilityDefault() {
		if (visibilityMode == null) {
			visibilityMode = ListenerVisibilityMode.STANDARD;
		}
	}

	public boolean isGhost() {
		return visibilityMode == ListenerVisibilityMode.GHOST;
	}

	/**
	 * Public surfaces fail closed until onboarding has an explicit visibility
	 * choice. This is intentionally broader than {@link #isGhost()}.
	 */
	public boolean isPubliclyRestricted() {
		return !visibilityChoiceCompleted || isGhost();
	}
}
