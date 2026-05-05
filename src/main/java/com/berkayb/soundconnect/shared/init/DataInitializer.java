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
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static com.berkayb.soundconnect.modules.role.enums.PermissionEnum.*;
import static com.berkayb.soundconnect.modules.role.enums.RoleEnum.*;

@Component
@ConditionalOnProperty(value = "app.data.init.enabled", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
@Slf4j
public class DataInitializer {
	private final RoleRepository roleRepository;
	private final PermissionRepository permissionRepository;
	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;
	private final LocationEntityFinder locationEntityFinder;
	
	@PostConstruct
	public void initData() {
		
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
				MANAGE_VENUES,
				MANAGE_PROMOTIONS,
				MANAGE_INSTRUMENTS,
				MANAGE_PROFILES,
				MANAGE_DM,
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
		
		// default owner kullaniciyi olustur
		if (userRepository.findByUsername("basol").isEmpty()) {
			log.info("default owner olusturuluyor...");
			
			Role owner = roleRepository.findByName(ROLE_OWNER.name())
			                           .orElseThrow(() -> new RuntimeException("ROLE_OWNER bulunamadi"));
			
			City cityEntity = locationEntityFinder.getCityByName("Ankara");
			User admin = User.builder()
			                 .username("basol")
			                 .password(passwordEncoder.encode("raprap12334"))
			                 .email("admin@soundconnect.com")
			                 .phone("05555555555")
			                 .city(cityEntity)
			                 .gender(Gender.MALE)
			                 .status(UserStatus.ACTIVE)
							 .emailVerified(true)
			                 .roles(Set.of(owner))
			                 .createdAt(LocalDateTime.now())
			                 .updatedAt(LocalDateTime.now())
			                 .build();
			
			userRepository.save(admin);
			log.info("owner kullanici olusturuldu: basol / raprap12334");
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
