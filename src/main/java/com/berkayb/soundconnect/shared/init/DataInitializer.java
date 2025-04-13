package com.berkayb.soundconnect.shared.init;

import com.berkayb.soundconnect.role.entity.Permission;
import com.berkayb.soundconnect.role.entity.Role;
import com.berkayb.soundconnect.role.enums.PermissionEnum;
import com.berkayb.soundconnect.role.repository.PermissionRepository;
import com.berkayb.soundconnect.role.repository.RoleRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
@RequiredArgsConstructor
@Slf4j
public class DataInitializer {
	private final RoleRepository roleRepository;
	private final PermissionRepository permissionRepository;
	
	@PostConstruct // proje calistiktan sonra calissin
	public void initData() {
		// eger veri varsa tekrar eklememek icin kontrol et
		if (roleRepository.count() > 0 || permissionRepository.count() > 0) {
			log.info("Roles already exist");
			return;
		}
		// Default izinler
		Permission readUser = Permission.builder().name(PermissionEnum.READ_USER.name()).build();
		Permission writeUser = Permission.builder().name(PermissionEnum.WRITE_USER.name()).build();
		Permission deleteUser = Permission.builder().name(PermissionEnum.DELETE_USER.name()).build();
		
		permissionRepository.saveAll(List.of(readUser, writeUser, deleteUser));
		
		// Default roller ve ilgili rollerin izinleri
		Role userRole = Role.builder()
				.name("ROLE_USER")
				.permissions(Set.of(readUser))
				.build();
		
		Role adminRole = Role.builder()
				.name("ROLE_ADMIN")
				.permissions(Set.of(readUser, writeUser, deleteUser)) // yeni bir izin eklediginde buraya ugramayi unutma :D
				.build();
		
		roleRepository.saveAll(List.of(userRole, adminRole));
		
		log.info("Default Roles & Permissions added");
	}
	
}