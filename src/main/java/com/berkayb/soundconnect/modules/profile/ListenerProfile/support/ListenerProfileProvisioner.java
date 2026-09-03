package com.berkayb.soundconnect.modules.profile.ListenerProfile.support;

import com.berkayb.soundconnect.modules.profile.ListenerProfile.entity.ListenerProfile;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.repository.ListenerProfileRepository;
import com.berkayb.soundconnect.modules.profile.shared.type.PersonalProfileTypePolicy;
import com.berkayb.soundconnect.modules.role.enums.RoleEnum;
import com.berkayb.soundconnect.modules.user.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Idempotently repairs the one-to-one listener-profile aggregate.
 *
 * <p>The user row is the creation mutex. This is important because a missing
 * one-to-one row cannot itself be locked: concurrent onboarding retries would
 * otherwise both observe absence and race on the unique {@code user_id}
 * constraint. Callers always run inside a wider transaction, so provisioning
 * and the operation that required it commit or roll back together.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ListenerProfileProvisioner {

	private final ListenerProfileRepository listenerProfileRepository;
	private final PersonalProfileTypePolicy personalProfileTypePolicy;

	@Transactional(propagation = Propagation.MANDATORY)
	public ListenerProfile ensureExistsForUpdate(UUID userId) {
		User lockedUser = personalProfileTypePolicy.lockAndAssertCanAcquire(
				userId,
				RoleEnum.ROLE_LISTENER
		);

		return listenerProfileRepository.findByUserIdForUpdate(userId)
				.orElseGet(() -> createEmptyProfile(lockedUser));
	}

	private ListenerProfile createEmptyProfile(User lockedUser) {
		ListenerProfile profile = ListenerProfile.builder()
				.user(lockedUser)
				.visibilityMode(ListenerVisibilityMode.STANDARD)
				.visibilityChoiceCompleted(false)
				.build();
		ListenerProfile saved = listenerProfileRepository.saveAndFlush(profile);
		log.info("Provisioned missing listener profile aggregate. userId={} profileId={}",
				lockedUser.getId(), saved.getId());
		return saved;
	}
}
