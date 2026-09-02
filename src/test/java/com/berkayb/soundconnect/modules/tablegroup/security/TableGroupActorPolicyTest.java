package com.berkayb.soundconnect.modules.tablegroup.security;

import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.role.enums.RoleEnum;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TableGroupActorPolicyTest {

	@ParameterizedTest
	@EnumSource(
			value = RoleEnum.class,
			mode = EnumSource.Mode.EXCLUDE,
			names = {"ROLE_VENUE", "ROLE_STUDIO"}
	)
	void requireEligible_shouldPreserveEveryExistingNonInstitutionalRole(RoleEnum role) {
		assertThatCode(() -> TableGroupActorPolicy.requireEligible(userWithRoles(role)))
				.doesNotThrowAnyException();
	}

	@ParameterizedTest
	@EnumSource(value = RoleEnum.class, names = {"ROLE_VENUE", "ROLE_STUDIO"})
	void requireEligible_shouldRejectInstitutionalRole(RoleEnum role) {
		assertThatThrownBy(() -> TableGroupActorPolicy.requireEligible(userWithRoles(role)))
				.isInstanceOf(SoundConnectException.class)
				.hasFieldOrPropertyWithValue(
						"errorType", ErrorType.TABLE_GROUP_ACTOR_ROLE_FORBIDDEN);
	}

	@Test
	void requireEligible_shouldRejectMixedRoleWhenAnyRoleIsInstitutional() {
		assertThatThrownBy(() -> TableGroupActorPolicy.requireEligible(userWithRoles(
				RoleEnum.ROLE_MUSICIAN, RoleEnum.ROLE_VENUE)))
				.isInstanceOf(SoundConnectException.class)
				.hasFieldOrPropertyWithValue(
						"errorType", ErrorType.TABLE_GROUP_ACTOR_ROLE_FORBIDDEN);
	}

	@Test
	void requireEligible_shouldRejectMissingUserIdentity() {
		assertThatThrownBy(() -> TableGroupActorPolicy.requireEligible(null))
				.isInstanceOf(SoundConnectException.class)
				.hasFieldOrPropertyWithValue("errorType", ErrorType.UNAUTHORIZED);
	}

	private User userWithRoles(RoleEnum... roles) {
		Set<Role> roleEntities = Arrays.stream(roles)
				.map(role -> Role.builder().id(UUID.randomUUID()).name(role.name()).build())
				.collect(Collectors.toSet());
		return User.builder()
				.id(UUID.randomUUID())
				.username("actor")
				.roles(roleEntities)
				.build();
	}
}
