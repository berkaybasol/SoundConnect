package com.berkayb.soundconnect.modules.tablegroup.security;

import com.berkayb.soundconnect.modules.role.enums.RoleEnum;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;

import java.util.Collection;
import java.util.Set;

/**
 * Authoritative account-type boundary for social table participation.
 *
 * <p>Table-group ownership and membership are always personal user identities.
 * Venue and studio accounts are institutional actors and may neither create a
 * table nor apply to one. A user carrying either forbidden role is rejected
 * even when another, otherwise eligible role is also present.</p>
 */
public final class TableGroupActorPolicy {

	private static final Set<String> FORBIDDEN_ROLE_NAMES = Set.of(
			RoleEnum.ROLE_VENUE.name(),
			RoleEnum.ROLE_STUDIO.name()
	);

	private TableGroupActorPolicy() {
	}

	public static void requireEligible(User actor) {
		if (actor == null) {
			throw new SoundConnectException(ErrorType.UNAUTHORIZED);
		}

		requireEligibleRoleNames(actor.getRoles() == null
				? Set.of()
				: actor.getRoles().stream()
						.filter(role -> role != null && role.getName() != null)
						.map(role -> role.getName())
						.toList());
	}

	public static void requireEligibleRoleNames(Collection<String> roleNames) {
		boolean hasForbiddenRole = roleNames != null
				&& roleNames.stream().anyMatch(FORBIDDEN_ROLE_NAMES::contains);
		if (hasForbiddenRole) {
			throw new SoundConnectException(ErrorType.TABLE_GROUP_ACTOR_ROLE_FORBIDDEN);
		}
	}
}
