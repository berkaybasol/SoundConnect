package com.berkayb.soundconnect.modules.profile.ListenerProfile.support;

import com.berkayb.soundconnect.modules.profile.ListenerProfile.repository.ListenerProfileRepository;
import com.berkayb.soundconnect.modules.role.enums.RoleEnum;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Server-authoritative routing decision for listener visibility onboarding.
 *
 * <p>An active listener with either a missing profile or an incomplete profile
 * is deliberately routed back to the chooser. This fail-closed behavior keeps
 * login, OTP verification and Google onboarding consistent across devices.</p>
 */
@Component
@RequiredArgsConstructor
public class ListenerProfileChoiceStatusReader {

	private final ListenerProfileRepository listenerProfileRepository;

	@Transactional(readOnly = true)
	public boolean requiresChoice(User user) {
		if (user == null
				|| user.getStatus() != UserStatus.ACTIVE
				|| user.getId() == null
				|| !hasListenerRole(user)) {
			return false;
		}

		return !listenerProfileRepository
				.existsByUserIdAndVisibilityChoiceCompletedTrue(user.getId());
	}

	private boolean hasListenerRole(User user) {
		return user.getRoles() != null
				&& user.getRoles().stream()
						.anyMatch(role -> role != null
								&& RoleEnum.ROLE_LISTENER.name().equals(role.getName()));
	}
}
