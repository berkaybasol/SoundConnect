package com.berkayb.soundconnect.tools.simulation.seed.account;

import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.role.enums.RoleEnum;
import com.berkayb.soundconnect.modules.role.repository.RoleRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.AuthProvider;
import com.berkayb.soundconnect.modules.user.enums.Gender;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.shared.util.EmailUtils;
import com.berkayb.soundconnect.shared.util.UsernameUtils;
import com.berkayb.soundconnect.tools.simulation.SimulationProperties;
import com.berkayb.soundconnect.tools.simulation.SimulationRuntimeGuard;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Provisions the local-only administrator used to exercise real institutional
 * approval services. This infrastructure identity is not part of the 50-account
 * simulated population.
 */
@Component
@Profile("local & simulation")
@ConditionalOnProperty(prefix = "app.simulation", name = "enabled", havingValue = "true", matchIfMissing = false)
public class SimulationControlAdminProvisioner {

	static final String CONTROL_USERNAME = "simulation_control_admin";
	static final String CONTROL_EMAIL_LOCAL_PART = "control-admin";
	private static final String CONTROL_DESCRIPTION = "Local SoundConnect simulation control identity";

	private final SimulationRuntimeGuard runtimeGuard;
	private final SimulationProperties properties;
	private final RoleRepository roleRepository;
	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;

	public SimulationControlAdminProvisioner(
			SimulationRuntimeGuard runtimeGuard,
			SimulationProperties properties,
			RoleRepository roleRepository,
			UserRepository userRepository,
			PasswordEncoder passwordEncoder
	) {
		this.runtimeGuard = Objects.requireNonNull(runtimeGuard, "runtimeGuard");
		this.properties = Objects.requireNonNull(properties, "properties");
		this.roleRepository = Objects.requireNonNull(roleRepository, "roleRepository");
		this.userRepository = Objects.requireNonNull(userRepository, "userRepository");
		this.passwordEncoder = Objects.requireNonNull(passwordEncoder, "passwordEncoder");
	}

	@Transactional
	public SimulationControlAdmin provision() {
		runtimeGuard.assertRuntimeAllowed();

		String username = UsernameUtils.normalizeAndValidate(CONTROL_USERNAME);
		String email = controlEmail();
		String commonPassword = requireCommonPassword();
		Role adminRole = roleRepository.findByName(RoleEnum.ROLE_ADMIN.name())
				.orElseThrow(() -> new IllegalStateException("Simulation control admin requires ROLE_ADMIN"));

		Optional<User> byUsername = userRepository.findByUsername(username);
		Optional<User> byEmail = userRepository.findByEmail(email);
		if (byUsername.isPresent() || byEmail.isPresent()) {
			User existing = resolveSingleExistingIdentity(byUsername, byEmail);
			assertExpectedIdentity(existing, username, email, commonPassword);
			return toResult(existing, false);
		}

		User admin = User.builder()
				.username(username)
				.email(email)
				.password(passwordEncoder.encode(commonPassword))
				.description(CONTROL_DESCRIPTION)
				.gender(Gender.OTHER)
				.provider(AuthProvider.LOCAL)
				.status(UserStatus.ACTIVE)
				.emailVerified(true)
				.roles(Set.of(adminRole))
				.build();
		return toResult(userRepository.saveAndFlush(admin), true);
	}

	private String requireCommonPassword() {
		String password = properties.getCommonPassword();
		if (password == null || password.isBlank()) {
			throw new IllegalStateException("Simulation common password is required");
		}
		return password;
	}

	private String controlEmail() {
		String suffix = properties.getEmailSuffix();
		if (suffix == null || !suffix.startsWith("@") || !suffix.endsWith(".invalid")) {
			throw new IllegalStateException("Simulation control admin requires a .invalid email suffix");
		}
		return EmailUtils.normalize(CONTROL_EMAIL_LOCAL_PART + suffix);
	}

	private User resolveSingleExistingIdentity(Optional<User> byUsername, Optional<User> byEmail) {
		if (byUsername.isEmpty() || byEmail.isEmpty()
				|| byUsername.get().getId() == null
				|| !byUsername.get().getId().equals(byEmail.get().getId())) {
			throw conflictingIdentity();
		}
		return byUsername.get();
	}

	private void assertExpectedIdentity(
			User user,
			String expectedUsername,
			String expectedEmail,
			String commonPassword
	) {
		boolean exactlyAdmin = user.getRoles() != null
				&& user.getRoles().size() == 1
				&& user.getRoles().stream()
						.anyMatch(role -> role != null
								&& RoleEnum.ROLE_ADMIN.name().equals(role.getName()));
		boolean matches = expectedUsername.equals(user.getUsername())
				&& expectedEmail.equals(user.getEmail())
				&& user.getStatus() == UserStatus.ACTIVE
				&& Boolean.TRUE.equals(user.getEmailVerified())
				&& user.getProvider() == AuthProvider.LOCAL
				&& exactlyAdmin
				&& user.getPassword() != null
				&& passwordEncoder.matches(commonPassword, user.getPassword());
		if (!matches) throw conflictingIdentity();
	}

	private IllegalStateException conflictingIdentity() {
		return new IllegalStateException("Simulation control admin identity conflicts with existing data");
	}

	private SimulationControlAdmin toResult(User user, boolean created) {
		if (user == null || user.getId() == null) {
			throw new IllegalStateException("Simulation control admin was not persisted");
		}
		return new SimulationControlAdmin(user.getId(), user.getUsername(), user.getEmail(), created);
	}
}
