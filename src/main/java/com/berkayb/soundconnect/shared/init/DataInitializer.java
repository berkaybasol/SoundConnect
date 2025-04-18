package com.berkayb.soundconnect.shared.init;

import com.berkayb.soundconnect.role.entity.Permission;
import com.berkayb.soundconnect.role.entity.Role;
import com.berkayb.soundconnect.role.enums.PermissionEnum;
import com.berkayb.soundconnect.role.repository.PermissionRepository;
import com.berkayb.soundconnect.role.repository.RoleRepository;
import com.berkayb.soundconnect.user.entity.User;
import com.berkayb.soundconnect.user.enums.City;
import com.berkayb.soundconnect.user.enums.Gender;
import com.berkayb.soundconnect.user.enums.UserStatus;
import com.berkayb.soundconnect.user.repository.UserRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Component
@RequiredArgsConstructor
@Slf4j
public class DataInitializer {
	private final RoleRepository roleRepository;
	private final PermissionRepository permissionRepository;
	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;
	
	
	@PostConstruct // proje calistiktan sonra calissin
	public void initData() {
		
		// eger veri varsa tekrar eklememek icin kontrol et
		if (roleRepository.count() > 0 || permissionRepository.count() > 0) {
			log.info("Roles already exist");
			return;
		}
		
								// --------------> DEFAULT IZINLER <--------------
		// USER
		Permission readUser = Permission.builder().name(PermissionEnum.READ_USER.name()).build();
		Permission writeUser = Permission.builder().name(PermissionEnum.WRITE_USER.name()).build();
		Permission deleteUser = Permission.builder().name(PermissionEnum.DELETE_USER.name()).build();
		
		// VENUE
		Permission readVenue = Permission.builder().name(PermissionEnum.READ_VENUE.name()).build();
		Permission writeVenue = Permission.builder().name(PermissionEnum.WRITE_VENUE.name()).build();
		Permission deleteVenue = Permission.builder().name(PermissionEnum.DELETE_VENUE.name()).build();
		Permission assignArtistToVenue = Permission.builder().name(PermissionEnum.ASSIGN_ARTIST_TO_VENUE.name()).build();
		
		// butun izinleri kaydet
		permissionRepository.saveAll(List.of(
				readUser,
				writeUser,
				deleteUser,
				readVenue,
				writeVenue,
				deleteVenue,
				assignArtistToVenue
		));
		
		//              --------------> DEFAULT ROLLER VE ILGILI ROLLERIN IZINLERI <--------------
		Role userRole = Role.builder()
		                    .name("ROLE_USER")
		                    .permissions(Set.of(readUser))
		                    .build();
		
		Role adminRole = Role.builder()
		                     .name("ROLE_ADMIN")
		                     .permissions(Set.of(
									 readUser,
									 writeUser,
									 deleteUser,
									 readVenue,
									 writeVenue,
									 deleteVenue,
									 assignArtistToVenue)) // yeni bir izin eklediginde buraya ugramayi unutma :D
		                     .build();
		
		Role venueRole = Role.builder()
				.name("ROLE_VENUE")
				.permissions(Set.of(readVenue, writeVenue, deleteVenue, assignArtistToVenue))
				.build();
		
		roleRepository.saveAll(List.of(userRole, adminRole, venueRole));
		
		log.info("Default Roles & Permissions added");
		
		if (userRepository.findByUsername("admin").isEmpty()) {
			log.info("Admin user not found, creating default admin...");
			
			// ROLE_ADMIN'i tekrar veritabanından çekiyoruz çünkü yukarıdaki saveAll() işlemi sonrası Entity'ler detached olabilir
			Role persistedAdminRole = roleRepository.findByName("ROLE_ADMIN")
			                                        .orElseThrow(() -> new RuntimeException("ROLE_ADMIN not found"));
			
			
			
			// default admin
			User admin = User.builder()
			                 .username("basol")
			                 .password(passwordEncoder.encode("raprap12334")) // Şifreyi encode etmezsen giriş yapılamaz!
			                 .email("admin@soundconnect.com")
			                 .phone("05555555555")
			                 .city(City.ANKARA)
			                 .gender(Gender.MALE) 
			                 .status(UserStatus.ACTIVE)
			                 .roles(Set.of(persistedAdminRole))
			                 .createdAt(LocalDateTime.now())
			                 .updatedAt(LocalDateTime.now())
			                 .build();
			
			userRepository.save(admin);
			
			log.info("Default admin user created with username: basol / password: raprap12334");
		}
		
		
	}
}