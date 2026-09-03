package com.berkayb.soundconnect.modules.profile.shared.type;

import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.role.enums.RoleEnum;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PersonalProfileTypePolicyTest {

	private final UserRepository userRepository = mock(UserRepository.class);
	private final PersonalProfileTypePolicy policy = new PersonalProfileTypePolicy(userRepository);

	@Test
	void rolelessAccountCanAcquireItsFirstPersonalTypeUnderUserLock() {
		User user = user(Set.of());
		when(userRepository.findByIdForUpdate(user.getId())).thenReturn(Optional.of(user));
		when(userRepository.findExistingPersonalProfileRoleNames(user.getId())).thenReturn(Set.of());

		assertThat(policy.lockAndAssertCanAcquire(user.getId(), RoleEnum.ROLE_LISTENER)).isSameAs(user);
		verify(userRepository).findByIdForUpdate(user.getId());
	}

	@Test
	void acquiringTheSamePersonalTypeIsIdempotent() {
		User user = user(Set.of(role(RoleEnum.ROLE_STUDIO)));
		when(userRepository.findByIdForUpdate(user.getId())).thenReturn(Optional.of(user));
		when(userRepository.findExistingPersonalProfileRoleNames(user.getId()))
				.thenReturn(Set.of(RoleEnum.ROLE_STUDIO.name()));

		assertThatCode(() -> policy.lockAndAssertCanAcquire(user.getId(), RoleEnum.ROLE_STUDIO))
				.doesNotThrowAnyException();
	}

	@Test
	void differentRoleOrPersistedProfileBlocksAcquisition() {
		User roleConflict = user(Set.of(role(RoleEnum.ROLE_MUSICIAN)));
		when(userRepository.findExistingPersonalProfileRoleNames(roleConflict.getId())).thenReturn(Set.of());

		assertImmutable(() -> policy.assertCanAcquire(roleConflict, RoleEnum.ROLE_VENUE));

		User profileConflict = user(Set.of());
		when(userRepository.findExistingPersonalProfileRoleNames(profileConflict.getId()))
				.thenReturn(Set.of(RoleEnum.ROLE_PRODUCER.name()));

		assertImmutable(() -> policy.assertCanAcquire(profileConflict, RoleEnum.ROLE_LISTENER));
	}

	@Test
	void corruptMultiplePersonalTypesFailClosedEvenWhenTargetIsAmongThem() {
		User user = user(Set.of(role(RoleEnum.ROLE_LISTENER)));
		when(userRepository.findExistingPersonalProfileRoleNames(user.getId()))
				.thenReturn(Set.of(RoleEnum.ROLE_LISTENER.name(), RoleEnum.ROLE_MUSICIAN.name()));

		assertImmutable(() -> policy.assertCanAcquire(user, RoleEnum.ROLE_LISTENER));
	}

	@Test
	void adminRoleReplacementCanRepairSameTypeButCannotRemoveOrChangeIt() {
		User listener = user(Set.of(role(RoleEnum.ROLE_LISTENER)));
		when(userRepository.findExistingPersonalProfileRoleNames(listener.getId()))
				.thenReturn(Set.of(RoleEnum.ROLE_LISTENER.name()));

		assertThatCode(() -> policy.assertRoleReplacementAllowed(
				listener, role(RoleEnum.ROLE_LISTENER))).doesNotThrowAnyException();
		assertImmutable(() -> policy.assertRoleReplacementAllowed(
				listener, role(RoleEnum.ROLE_MUSICIAN)));
		assertImmutable(() -> policy.assertRoleReplacementAllowed(
				listener, role(RoleEnum.ROLE_ADMIN)));
	}

	@Test
	void adminMayAssignFirstPersonalRoleToTrulyRolelessProfilelessAccount() {
		User roleless = user(Set.of());
		when(userRepository.findExistingPersonalProfileRoleNames(roleless.getId())).thenReturn(Set.of());

		assertThatCode(() -> policy.assertRoleReplacementAllowed(
				roleless, role(RoleEnum.ROLE_ORGANIZER))).doesNotThrowAnyException();
	}

	@Test
	void genericAdminCannotConvertAnEstablishedNonPersonalAccount() {
		User staff = user(Set.of(role(RoleEnum.ROLE_ADMIN)));
		when(userRepository.findExistingPersonalProfileRoleNames(staff.getId())).thenReturn(Set.of());

		assertImmutable(() -> policy.assertRoleReplacementAllowed(
				staff, role(RoleEnum.ROLE_LISTENER)));
	}

	@Test
	void missingAccountFailsBeforeAcquisition() {
		UUID userId = UUID.randomUUID();
		when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> policy.lockAndAssertCanAcquire(userId, RoleEnum.ROLE_STUDIO))
				.isInstanceOfSatisfying(SoundConnectException.class, exception ->
						assertThat(exception.getErrorType()).isEqualTo(ErrorType.USER_NOT_FOUND));
	}

	@Test
	void acquisitionEntryPointRequiresAnExistingTransaction() throws NoSuchMethodException {
		Transactional annotation = PersonalProfileTypePolicy.class
				.getMethod("lockAndAssertCanAcquire", UUID.class, RoleEnum.class)
				.getAnnotation(Transactional.class);

		assertThat(annotation).isNotNull();
		assertThat(annotation.propagation()).isEqualTo(Propagation.MANDATORY);
	}

	private void assertImmutable(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
		assertThatThrownBy(callable)
				.isInstanceOfSatisfying(SoundConnectException.class, exception ->
						assertThat(exception.getErrorType()).isEqualTo(ErrorType.PROFILE_TYPE_IMMUTABLE));
	}

	private User user(Set<Role> roles) {
		return User.builder().id(UUID.randomUUID()).username("person").password("pw").roles(roles).build();
	}

	private Role role(RoleEnum role) {
		return Role.builder().id(UUID.randomUUID()).name(role.name()).build();
	}
}
