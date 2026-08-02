package com.berkayb.soundconnect.shared.init;

import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.support.LocationEntityFinder;
import com.berkayb.soundconnect.modules.role.entity.Permission;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.role.enums.PermissionEnum;
import com.berkayb.soundconnect.modules.role.repository.PermissionRepository;
import com.berkayb.soundconnect.modules.role.repository.RoleRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.Gender;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.shared.util.EmailUtils;
import com.berkayb.soundconnect.shared.util.UsernameUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static com.berkayb.soundconnect.modules.role.enums.PermissionEnum.*;
import static com.berkayb.soundconnect.modules.role.enums.RoleEnum.*;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
@ConditionalOnProperty(value = "app.data.init.enabled", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements ApplicationRunner {
	private final RoleRepository roleRepository;
	private final PermissionRepository permissionRepository;
	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;
	private final LocationEntityFinder locationEntityFinder;

	@Value("${app.data.init.owner.enabled:false}")
	private boolean ownerBootstrapEnabled;
	@Value("${app.data.init.owner.username:}")
	private String ownerUsername;
	@Value("${app.data.init.owner.password:}")
	private String ownerPassword;
	@Value("${app.data.init.owner.email:}")
	private String ownerEmail;
	@Value("${app.data.init.owner.phone:}")
	private String ownerPhone;
	@Value("${app.data.init.owner.city:}")
	private String ownerCity;
	@Value("${app.data.init.owner.minimum-password-length:16}")
	private int ownerMinimumPasswordLength = 16;

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		
		log.info("roller ve izinler senkronize ediliyor...");
		
		syncPermissions();
		
		// kayitli izinleri map yapisina donustur
		Map<String, Permission> permissionMap = permissionRepository.findAll().stream()
		                                                            .collect(Collectors.toMap(Permission::getName, p -> p));
		
		Role userRole = upsertRole(ROLE_USER.name(), permissions(permissionMap, READ_USER));
		Role musicianRole = upsertRole(ROLE_MUSICIAN.name(), permissions(permissionMap, READ_USER));
		Role studioRole = upsertRole(ROLE_STUDIO.name(), permissions(permissionMap, READ_USER));
		Role listenerRole = upsertRole(ROLE_LISTENER.name(), permissions(permissionMap, READ_USER));
		Role organizerRole = upsertRole(ROLE_ORGANIZER.name(), permissions(permissionMap, READ_USER));
		Role producerRole = upsertRole(ROLE_PRODUCER.name(), permissions(permissionMap, READ_USER));
		Role moderatorRole = upsertRole(ROLE_ADMIN.name(), permissions(
				permissionMap,
				READ_USER,
				WRITE_USER,
				DELETE_USER,
				READ_ALL_USERS,
				READ_USERS,
				ADMIN_PANEL_ACCESS,
				MANAGE_USERS,
				READ_VENUE,
				WRITE_VENUE,
				DELETE_VENUE,
				ASSIGN_ARTIST_TO_VENUE,
				READ_LOCATION,
				WRITE_LOCATION,
				DELETE_LOCATION,
				MANAGE_LOCATIONS,
				MANAGE_VENUE_APPLICATIONS,
				MANAGE_STUDIO_APPLICATIONS,
				MANAGE_VENUES,
				MANAGE_PROMOTIONS,
				MANAGE_INSTRUMENTS,
				MANAGE_PROFILES,
				MANAGE_DM,
				MANAGE_BACKLINE_CATALOG,
				DELETE_COMMENT
		));
		Role venueRole = upsertRole(ROLE_VENUE.name(), permissions(
				permissionMap,
				READ_USER,
				READ_VENUE,
				ASSIGN_ARTIST_TO_VENUE
		));
		Role ownerRole = upsertRole(ROLE_OWNER.name(), new HashSet<>(permissionMap.values()));
		
		roleRepository.saveAll(List.of(userRole, moderatorRole, venueRole, ownerRole, musicianRole, listenerRole, studioRole, organizerRole, producerRole));
		
		log.info("roller ve izinler senkronize edildi.");
		
		bootstrapOwnerIfExplicitlyEnabled();
	}

	private void bootstrapOwnerIfExplicitlyEnabled() {
		if (!ownerBootstrapEnabled) {
			log.info("owner bootstrap disabled");
			return;
		}

		requireConfigured("username", ownerUsername);
		requireConfigured("password", ownerPassword);
		requireConfigured("email", ownerEmail);
		requireConfigured("phone", ownerPhone);
		requireConfigured("city", ownerCity);
		String normalizedOwnerUsername = UsernameUtils.normalize(ownerUsername);
		if (!UsernameUtils.hasValidCanonicalLength(normalizedOwnerUsername)) {
			throw new IllegalStateException("Owner bootstrap username must contain between "
					+ UsernameUtils.MIN_LENGTH + " and " + UsernameUtils.MAX_LENGTH + " characters");
		}
		if (ownerMinimumPasswordLength < 10 || ownerMinimumPasswordLength > 128) {
			throw new IllegalStateException("Owner bootstrap minimum password length must be between 10 and 128");
		}
		if (ownerPassword.length() < ownerMinimumPasswordLength) {
			throw new IllegalStateException("Owner bootstrap password must contain at least "
					+ ownerMinimumPasswordLength + " characters");
		}

		Role ownerRole = roleRepository.findByName(ROLE_OWNER.name())
		                               .orElseThrow(() -> new IllegalStateException("ROLE_OWNER bulunamadi"));
		Optional<User> existing = userRepository.findByUsername(normalizedOwnerUsername);
		if (existing.isPresent()) {
			boolean alreadyOwner = existing.get().getRoles().stream()
			                               .anyMatch(role -> ROLE_OWNER.name().equals(role.getName()));
			if (!alreadyOwner) {
				throw new IllegalStateException("Configured owner bootstrap username belongs to a non-owner account");
			}
			log.info("owner bootstrap account already exists username={}", normalizedOwnerUsername);
			return;
		}
		String normalizedOwnerEmail = EmailUtils.normalize(ownerEmail);
		if (userRepository.existsByEmail(normalizedOwnerEmail)) {
			throw new IllegalStateException("Configured owner bootstrap email is already in use");
		}

		City cityEntity = locationEntityFinder.getCityByName(ownerCity);
		User owner = User.builder()
		                 .username(normalizedOwnerUsername)
		                 .password(passwordEncoder.encode(ownerPassword))
		                 .email(normalizedOwnerEmail)
		                 .phone(ownerPhone)
		                 .city(cityEntity)
		                 .gender(Gender.OTHER)
		                 .status(UserStatus.ACTIVE)
		                 .emailVerified(true)
		                 .roles(Set.of(ownerRole))
		                 .createdAt(LocalDateTime.now())
		                 .updatedAt(LocalDateTime.now())
		                 .build();

		User savedOwner = userRepository.save(owner);
		log.warn("owner bootstrap account created id={} username={}; disable bootstrap immediately",
		         savedOwner.getId(), savedOwner.getUsername());
	}

	private void requireConfigured(String property, String value) {
		if (!StringUtils.hasText(value)) {
			throw new IllegalStateException("Owner bootstrap is enabled but app.data.init.owner." + property + " is empty");
		}
	}
	
	private void syncPermissions() {
		Arrays.stream(PermissionEnum.values())
		      .forEach(permissionEnum -> permissionRepository.findByName(permissionEnum.name())
		                                                    .orElseGet(() -> permissionRepository.save(
				                                                    Permission.builder()
				                                                              .name(permissionEnum.name())
				                                                              .build()
		                                                    )));
	}
	
	private Role upsertRole(String roleName, Set<Permission> permissions) {
		Role role = roleRepository.findByName(roleName)
		                          .orElseGet(() -> Role.builder().name(roleName).build());
		role.setPermissions(permissions);
		return roleRepository.save(role);
	}
	
	private Set<Permission> permissions(Map<String, Permission> permissionMap, PermissionEnum... permissionEnums) {
		Set<Permission> permissions = new HashSet<>();
		for (PermissionEnum permissionEnum : permissionEnums) {
			permissions.add(Objects.requireNonNull(
					permissionMap.get(permissionEnum.name()),
					permissionEnum.name() + " eksik"
			));
		}
		return permissions;
	}
}
