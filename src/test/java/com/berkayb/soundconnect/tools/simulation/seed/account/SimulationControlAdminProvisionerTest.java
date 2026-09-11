package com.berkayb.soundconnect.tools.simulation.seed.account;

import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.role.enums.RoleEnum;
import com.berkayb.soundconnect.modules.role.repository.RoleRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.AuthProvider;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.tools.simulation.SimulationProperties;
import com.berkayb.soundconnect.tools.simulation.SimulationRuntimeGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SimulationControlAdminProvisionerTest {

	private static final String PASSWORD = "SimulationOnly!2026";
	private static final String EMAIL = "control-admin@simulation.soundconnect.invalid";

	@Mock
	private SimulationRuntimeGuard runtimeGuard;
	@Mock
	private RoleRepository roleRepository;
	@Mock
	private UserRepository userRepository;
	@Mock
	private PasswordEncoder passwordEncoder;

	private SimulationProperties properties;
	private Role adminRole;

	@BeforeEach
	void setUp() {
		properties = new SimulationProperties();
		properties.setEnabled(true);
		properties.setEmailSuffix("@simulation.soundconnect.invalid");
		properties.setCommonPassword(PASSWORD);
		adminRole = Role.builder().name(RoleEnum.ROLE_ADMIN.name()).build();
		lenient().when(roleRepository.findByName(RoleEnum.ROLE_ADMIN.name()))
				.thenReturn(Optional.of(adminRole));
	}

	@Test
	void createsVerifiedActiveAdminWithoutAddingItToTheManifestPopulation() {
		when(userRepository.findByUsername(SimulationControlAdminProvisioner.CONTROL_USERNAME))
				.thenReturn(Optional.empty());
		when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());
		when(passwordEncoder.encode(PASSWORD)).thenReturn("encoded-password");
		UUID id = UUID.randomUUID();
		when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
			User user = invocation.getArgument(0);
			user.setId(id);
			return user;
		});

		SimulationControlAdmin result = provisioner().provision();

		assertThat(result).isEqualTo(new SimulationControlAdmin(
				id, SimulationControlAdminProvisioner.CONTROL_USERNAME, EMAIL, true));
		ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
		verify(userRepository).saveAndFlush(saved.capture());
		assertThat(saved.getValue().getStatus()).isEqualTo(UserStatus.ACTIVE);
		assertThat(saved.getValue().getEmailVerified()).isTrue();
		assertThat(saved.getValue().getProvider()).isEqualTo(AuthProvider.LOCAL);
		assertThat(saved.getValue().getRoles()).containsExactly(adminRole);
		assertThat(saved.getValue().getPassword()).isEqualTo("encoded-password");
		verify(runtimeGuard).assertRuntimeAllowed();
	}

	@Test
	void returnsAnExactExistingIdentityWithoutWriting() {
		UUID id = UUID.randomUUID();
		User existing = existingAdmin(id);
		when(userRepository.findByUsername(SimulationControlAdminProvisioner.CONTROL_USERNAME))
				.thenReturn(Optional.of(existing));
		when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(existing));
		when(passwordEncoder.matches(PASSWORD, "encoded-password")).thenReturn(true);

		SimulationControlAdmin result = provisioner().provision();

		assertThat(result).isEqualTo(new SimulationControlAdmin(
				id, SimulationControlAdminProvisioner.CONTROL_USERNAME, EMAIL, false));
		verify(userRepository, never()).saveAndFlush(any(User.class));
		verify(passwordEncoder, never()).encode(any());
	}

	@Test
	void failsClosedWhenOnlyOneSideOfTheReservedIdentityExists() {
		User existing = existingAdmin(UUID.randomUUID());
		when(userRepository.findByUsername(SimulationControlAdminProvisioner.CONTROL_USERNAME))
				.thenReturn(Optional.of(existing));
		when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> provisioner().provision())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("conflicts with existing data");

		verify(userRepository, never()).saveAndFlush(any(User.class));
	}

	@Test
	void failsClosedWhenReservedIdentityHasWrongSecurityState() {
		User existing = existingAdmin(UUID.randomUUID());
		existing.setEmailVerified(false);
		when(userRepository.findByUsername(SimulationControlAdminProvisioner.CONTROL_USERNAME))
				.thenReturn(Optional.of(existing));
		when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(existing));

		assertThatThrownBy(() -> provisioner().provision())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("conflicts with existing data");

		verify(userRepository, never()).saveAndFlush(any(User.class));
	}

	@Test
	void requiresTheSeededAdminRole() {
		when(roleRepository.findByName(RoleEnum.ROLE_ADMIN.name())).thenReturn(Optional.empty());

		assertThatThrownBy(() -> provisioner().provision())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("ROLE_ADMIN");
	}

	@Test
	void refusesAControlIdentityOutsideTheReservedInvalidDomain() {
		properties.setEmailSuffix("@example.com");

		assertThatThrownBy(() -> provisioner().provision())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining(".invalid email suffix");

		verify(userRepository, never()).saveAndFlush(any(User.class));
	}

	private SimulationControlAdminProvisioner provisioner() {
		return new SimulationControlAdminProvisioner(
				runtimeGuard, properties, roleRepository, userRepository, passwordEncoder);
	}

	private User existingAdmin(UUID id) {
		return User.builder()
				.id(id)
				.username(SimulationControlAdminProvisioner.CONTROL_USERNAME)
				.email(EMAIL)
				.password("encoded-password")
				.provider(AuthProvider.LOCAL)
				.status(UserStatus.ACTIVE)
				.emailVerified(true)
				.roles(Set.of(adminRole))
				.build();
	}
}
