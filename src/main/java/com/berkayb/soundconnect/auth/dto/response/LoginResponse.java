package com.berkayb.soundconnect.auth.dto.response;

import com.berkayb.soundconnect.modules.role.entity.Permission;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;

import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

public record LoginResponse(
		String token,
		UserStatus status,
		UUID userId,
		String username,
		Set<String> roles,
		Set<String> permissions,
		boolean admin,
		boolean requiresListenerProfileChoice
) {
	public static LoginResponse fromUser(
			String token,
			User user,
			boolean requiresListenerProfileChoice
	) {
		Set<String> roles = user.getRoles().stream()
				.map(Role::getName)
				.collect(Collectors.toCollection(TreeSet::new));
		Set<String> permissions = user.getRoles().stream()
				.flatMap(role -> role.getPermissions().stream())
				.map(Permission::getName)
				.collect(Collectors.toCollection(TreeSet::new));
		user.getPermissions().stream()
				.map(Permission::getName)
				.forEach(permissions::add);
		boolean admin = roles.contains("ROLE_OWNER")
				|| roles.contains("ROLE_ADMIN")
				|| permissions.contains("ADMIN_PANEL_ACCESS");
		return new LoginResponse(
				token,
				user.getStatus(),
				user.getId(),
				user.getUsername(),
				roles,
				permissions,
				admin,
				requiresListenerProfileChoice
		);
	}
}
