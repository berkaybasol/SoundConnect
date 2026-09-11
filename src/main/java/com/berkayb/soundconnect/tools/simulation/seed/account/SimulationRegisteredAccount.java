package com.berkayb.soundconnect.tools.simulation.seed.account;

import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.AccountRole;

import java.util.UUID;

/** Materialized identity and lifecycle state for one manifest account. */
public record SimulationRegisteredAccount(
		String accountKey,
		UUID userId,
		AccountRole role,
		String username,
		String email,
		UserStatus status,
		boolean emailVerified,
		UUID applicationId,
		boolean created
) {
	public SimulationRegisteredAccount {
		if (accountKey == null || accountKey.isBlank()) {
			throw new IllegalArgumentException("account key is required");
		}
		if (userId == null) throw new IllegalArgumentException("userId is required");
		if (role == null) throw new IllegalArgumentException("account role is required");
		if (username == null || username.isBlank()) {
			throw new IllegalArgumentException("username is required");
		}
		if (email == null || email.isBlank()) {
			throw new IllegalArgumentException("email is required");
		}
		if (status == null) throw new IllegalArgumentException("user status is required");
	}
}
