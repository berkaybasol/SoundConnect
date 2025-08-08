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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static com.berkayb.soundconnect.modules.role.enums.PermissionEnum.*;
import static com.berkayb.soundconnect.modules.role.enums.RoleEnum.*;

@Component
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
		
		// eger veri onceden eklenmisse tekrar ekleme
		if (roleRepository.count() > 0 || permissionRepository.count() > 0) {
			log.info("roles and permissions zaten eklenmis.");
			return;
		}
		
		log.info("roller ve izinler ekleniyor...");
		
		// tum izinleri enumdan al ve kaydet
		List<Permission> allPermissions = Arrays.stream(PermissionEnum.values())
		                                        .map(p -> Permission.builder().name(p.name()).build())
		                                        .collect(Collectors.toList());
		permissionRepository.saveAll(allPermissions);
		
		// kayitli izinleri map yapisina donustur
		Map<String, Permission> permissionMap = permissionRepository.findAll().stream()
		                                                            .collect(Collectors.toMap(Permission::getName, p -> p));
		
		// user rolunu olustur
		Role userRole = Role.builder()
		                    .name(ROLE_USER.name())
		                    .permissions(Set.of(
				                    Objects.requireNonNull(permissionMap.get(READ_USER.name()), "READ_USER eksik")
		                    ))
		                    .build();
		
		// musician rolunu olustur
		Role musicianRole = Role.builder()
		                    .name(ROLE_MUSICIAN.name())
		                    .permissions(Set.of(
				                    Objects.requireNonNull(permissionMap.get(READ_USER.name()), "READ_USER eksik")
		                    ))
		                    .build();
		
		// studio rolunu olustur
		Role studioRole = Role.builder()
		                        .name(ROLE_STUDIO.name())
		                        .permissions(Set.of(
				                        Objects.requireNonNull(permissionMap.get(READ_USER.name()), "READ_USER eksik")
		                        ))
		                        .build();
		
		// listener rolunu olustur
		Role listenerRole = Role.builder()
		                        .name(ROLE_LISTENER.name())
		                        .permissions(Set.of(
				                        Objects.requireNonNull(permissionMap.get(READ_USER.name()), "READ_USER eksik")
		                        ))
		                        .build();
		
		// Organizer rolunu olustur
		Role organizerRole = Role.builder()
		                        .name(ROLE_ORGANIZER.name())
		                        .permissions(Set.of(
				                        Objects.requireNonNull(permissionMap.get(READ_USER.name()), "READ_USER eksik")
		                        ))
		                        .build();
		
		// Producer rolunu olustur
		Role producerRole = Role.builder()
		                         .name(ROLE_PRODUCER.name())
		                         .permissions(Set.of(
				                         Objects.requireNonNull(permissionMap.get(READ_USER.name()), "READ_USER eksik")
		                         ))
		                         .build();
		
		
		
		// moderator rolunu olustur
		Role moderatorRole = Role.builder()
		                         .name(ROLE_ADMIN.name())
		                         .permissions(Set.of(
				                         Objects.requireNonNull(permissionMap.get(READ_USER.name())),
				                         Objects.requireNonNull(permissionMap.get(WRITE_USER.name())),
				                         Objects.requireNonNull(permissionMap.get(DELETE_USER.name())),
				                         Objects.requireNonNull(permissionMap.get(READ_ALL_USERS.name())),
				                         Objects.requireNonNull(permissionMap.get(READ_VENUE.name())),
				                         Objects.requireNonNull(permissionMap.get(WRITE_VENUE.name())),
				                         Objects.requireNonNull(permissionMap.get(DELETE_VENUE.name())),
				                         Objects.requireNonNull(permissionMap.get(ASSIGN_ARTIST_TO_VENUE.name())),
				                         Objects.requireNonNull(permissionMap.get(READ_LOCATION.name())),
				                         Objects.requireNonNull(permissionMap.get(WRITE_LOCATION.name())),
				                         Objects.requireNonNull(permissionMap.get(DELETE_LOCATION.name()))
				                         
		                         ))
		                         .build();
		
		// venue rolunu olustur
		Role venueRole = Role.builder()
		                     .name(ROLE_VENUE.name())
		                     .permissions(Set.of(
				                     Objects.requireNonNull(permissionMap.get(READ_VENUE.name())),
				                     Objects.requireNonNull(permissionMap.get(ASSIGN_ARTIST_TO_VENUE.name()))
		                     ))
		                     .build();
		
		// owner (adminlerin ustu) rolunu olustur
		Role ownerRole = Role.builder()
		                     .name(ROLE_OWNER.name())
		                     .permissions(new HashSet<>(permissionMap.values()))
		                     .build();
		
		roleRepository.saveAll(List.of(userRole, moderatorRole, venueRole, ownerRole, musicianRole, listenerRole, studioRole, organizerRole, producerRole));
		
		log.info("roller ve izinler eklendi.");
		
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
			                 .roles(Set.of(owner))
			                 .createdAt(LocalDateTime.now())
			                 .updatedAt(LocalDateTime.now())
			                 .build();
			
			userRepository.save(admin);
			log.info("owner kullanici olusturuldu: basol / raprap12334");
		}
	}
}