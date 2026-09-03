package com.berkayb.soundconnect.modules.profile.shared.type;

import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.role.enums.RoleEnum;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * Enforces the one-personal-profile-type lifetime invariant.
 *
 * <p>Band membership is intentionally absent: a band is a separate aggregate,
 * not a replacement personal identity. Every acquisition path must first lock
 * the account row through this policy (or pass that already locked entity), so
 * two concurrent application/provisioning flows cannot grant different types.</p>
 */
@Component
@RequiredArgsConstructor
public class PersonalProfileTypePolicy {

	private static final Set<RoleEnum> PERSONAL_PROFILE_ROLES = Set.copyOf(EnumSet.of(
			RoleEnum.ROLE_LISTENER,
			RoleEnum.ROLE_MUSICIAN,
			RoleEnum.ROLE_VENUE,
			RoleEnum.ROLE_STUDIO,
			RoleEnum.ROLE_ORGANIZER,
			RoleEnum.ROLE_PRODUCER
	));

	private final UserRepository userRepository;

	/**
	 * Locks the account and validates acquisition of {@code targetRole}. An
	 * account with no personal type may acquire it; the same existing type is an
	 * idempotent repair/retry; every different or corrupt multi-type state fails.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public User lockAndAssertCanAcquire(UUID userId, RoleEnum targetRole) {
		User user = userRepository.findByIdForUpdate(userId)
		                          .orElseThrow(() -> new SoundConnectException(ErrorType.USER_NOT_FOUND));
		assertCanAcquire(user, targetRole);
		return user;
	}

	/**
	 * Variant for callers that already hold the user-row write lock.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void assertCanAcquire(User lockedUser, RoleEnum targetRole) {
		if (lockedUser == null || lockedUser.getId() == null) {
			throw new SoundConnectException(ErrorType.USER_NOT_FOUND);
		}
		if (!isPersonalProfileRole(targetRole)) {
			throw new SoundConnectException(ErrorType.ROLE_NOT_FOUND);
		}

		Set<RoleEnum> existingTypes = existingTypes(lockedUser);
		if (!existingTypes.isEmpty()
				&& !(existingTypes.size() == 1 && existingTypes.contains(targetRole))) {
			throw new SoundConnectException(ErrorType.PROFILE_TYPE_IMMUTABLE);
		}
	}

	/**
	 * Protects the generic admin replace-role operation from orphaning a personal
	 * profile as well as from changing it into a different personal type.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void assertRoleReplacementAllowed(User lockedUser, Role replacementRole) {
		if (lockedUser == null || lockedUser.getId() == null) {
			throw new SoundConnectException(ErrorType.USER_NOT_FOUND);
		}
		if (replacementRole == null || replacementRole.getName() == null) {
			throw new SoundConnectException(ErrorType.ROLE_NOT_FOUND);
		}

		Set<RoleEnum> existingTypes = existingTypes(lockedUser);
		RoleEnum replacementType = personalProfileRole(replacementRole.getName());
		if (existingTypes.isEmpty()) {
			// Generic administration is not an onboarding workflow. It may repair a
			// truly roleless account, but it must not turn an established staff/base
			// account into a personal profile merely by replacing its role set.
			if (replacementType != null && lockedUser.getRoles() != null
					&& !lockedUser.getRoles().isEmpty()) {
				throw new SoundConnectException(ErrorType.PROFILE_TYPE_IMMUTABLE);
			}
			return;
		}
		if (existingTypes.size() != 1 || replacementType == null
				|| !existingTypes.contains(replacementType)) {
			throw new SoundConnectException(ErrorType.PROFILE_TYPE_IMMUTABLE);
		}
	}

	public static boolean isPersonalProfileRole(RoleEnum role) {
		return role != null && PERSONAL_PROFILE_ROLES.contains(role);
	}

	private Set<RoleEnum> existingTypes(User user) {
		EnumSet<RoleEnum> types = EnumSet.noneOf(RoleEnum.class);
		if (user.getRoles() != null) {
			user.getRoles().stream()
			    .map(Role::getName)
			    .map(PersonalProfileTypePolicy::personalProfileRole)
			    .filter(role -> role != null)
			    .forEach(types::add);
		}
		Set<String> persistedProfileRoles = userRepository.findExistingPersonalProfileRoleNames(user.getId());
		if (persistedProfileRoles != null) {
			persistedProfileRoles.stream()
			                     .map(PersonalProfileTypePolicy::personalProfileRole)
			                     .filter(role -> role != null)
			                     .forEach(types::add);
		}
		return Set.copyOf(types);
	}

	private static RoleEnum personalProfileRole(String roleName) {
		if (roleName == null) return null;
		try {
			RoleEnum role = RoleEnum.valueOf(roleName);
			return isPersonalProfileRole(role) ? role : null;
		} catch (IllegalArgumentException ignored) {
			return null;
		}
	}
}
