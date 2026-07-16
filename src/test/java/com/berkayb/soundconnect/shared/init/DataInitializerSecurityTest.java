package com.berkayb.soundconnect.shared.init;

import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.support.LocationEntityFinder;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.role.enums.RoleEnum;
import com.berkayb.soundconnect.modules.role.repository.PermissionRepository;
import com.berkayb.soundconnect.modules.role.repository.RoleRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DataInitializerSecurityTest {
	@Mock RoleRepository roleRepository;
	@Mock PermissionRepository permissionRepository;
	@Mock UserRepository userRepository;
	@Mock PasswordEncoder passwordEncoder;
	@Mock LocationEntityFinder locationEntityFinder;

	private DataInitializer initializer;

	@BeforeEach
	void setUp() {
		initializer = new DataInitializer(
				roleRepository, permissionRepository, userRepository, passwordEncoder, locationEntityFinder);
	}

	@Test
	void ownerBootstrap_isDisabledByDefault() {
		ReflectionTestUtils.setField(initializer, "ownerBootstrapEnabled", false);

		ReflectionTestUtils.invokeMethod(initializer, "bootstrapOwnerIfExplicitlyEnabled");

		verifyNoInteractions(userRepository, passwordEncoder, locationEntityFinder);
	}

	@Test
	void ownerBootstrap_failsFastWhenEnabledWithoutSecret() {
		configureOwner("");

		assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(initializer, "bootstrapOwnerIfExplicitlyEnabled"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("password");
		verifyNoInteractions(userRepository, passwordEncoder, locationEntityFinder);
	}

	@Test
	void ownerBootstrap_persistsOnlyEncodedPassword() {
		String rawPassword = "a-strong-bootstrap-password";
		configureOwner(rawPassword);
		Role ownerRole = Role.builder().name(RoleEnum.ROLE_OWNER.name()).build();
		when(roleRepository.findByName(RoleEnum.ROLE_OWNER.name())).thenReturn(Optional.of(ownerRole));
		when(userRepository.findByUsername("bootstrap-owner")).thenReturn(Optional.empty());
		when(userRepository.existsByEmail("owner@example.test")).thenReturn(false);
		when(locationEntityFinder.getCityByName("Ankara")).thenReturn(City.builder().name("Ankara").build());
		when(passwordEncoder.encode(rawPassword)).thenReturn("encoded-password");
		when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

		ReflectionTestUtils.invokeMethod(initializer, "bootstrapOwnerIfExplicitlyEnabled");

		ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
		verify(userRepository).save(captor.capture());
		assertThat(captor.getValue().getPassword()).isEqualTo("encoded-password");
		assertThat(captor.getValue().getPassword()).isNotEqualTo(rawPassword);
		assertThat(captor.getValue().getRoles()).containsExactly(ownerRole);
	}

	@Test
	void ownerBootstrap_rejectsShortPasswordWithSecureDefault() {
		configureOwner("short-pass");

		assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(initializer, "bootstrapOwnerIfExplicitlyEnabled"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("16 characters");
		verifyNoInteractions(userRepository, passwordEncoder, locationEntityFinder);
	}

	@Test
	void ownerBootstrap_allowsExplicitLocalMinimumWithoutStoringRawPassword() {
		String rawPassword = "local-pass1";
		configureOwner(rawPassword);
		ReflectionTestUtils.setField(initializer, "ownerMinimumPasswordLength", 10);
		Role ownerRole = Role.builder().name(RoleEnum.ROLE_OWNER.name()).build();
		when(roleRepository.findByName(RoleEnum.ROLE_OWNER.name())).thenReturn(Optional.of(ownerRole));
		when(userRepository.findByUsername("bootstrap-owner")).thenReturn(Optional.empty());
		when(userRepository.existsByEmail("owner@example.test")).thenReturn(false);
		when(locationEntityFinder.getCityByName("Ankara")).thenReturn(City.builder().name("Ankara").build());
		when(passwordEncoder.encode(rawPassword)).thenReturn("encoded-local-password");
		when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

		ReflectionTestUtils.invokeMethod(initializer, "bootstrapOwnerIfExplicitlyEnabled");

		ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
		verify(userRepository).save(captor.capture());
		assertThat(captor.getValue().getPassword()).isEqualTo("encoded-local-password");
		assertThat(captor.getValue().getPassword()).isNotEqualTo(rawPassword);
	}

	private void configureOwner(String password) {
		ReflectionTestUtils.setField(initializer, "ownerBootstrapEnabled", true);
		ReflectionTestUtils.setField(initializer, "ownerUsername", "bootstrap-owner");
		ReflectionTestUtils.setField(initializer, "ownerPassword", password);
		ReflectionTestUtils.setField(initializer, "ownerEmail", "owner@example.test");
		ReflectionTestUtils.setField(initializer, "ownerPhone", "+905555555555");
		ReflectionTestUtils.setField(initializer, "ownerCity", "Ankara");
	}
}
