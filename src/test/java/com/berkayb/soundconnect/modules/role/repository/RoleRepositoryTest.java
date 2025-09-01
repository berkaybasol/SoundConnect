package com.berkayb.soundconnect.modules.role.repository;

import com.berkayb.soundconnect.modules.role.entity.Permission;
import com.berkayb.soundconnect.modules.role.entity.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"spring.flyway.enabled=false",
		"spring.sql.init.mode=never"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@ActiveProfiles("test")
@Tag("repo")
class RoleRepositoryTest {
	
	@Autowired RoleRepository roleRepository;
	@Autowired PermissionRepository permissionRepository;
	
	@BeforeEach
	void clean() {
		// ManyToMany: önce role'leri sil, sonra permission'ları
		roleRepository.deleteAll();
		permissionRepository.deleteAll();
	}
	
	@Test
	void save_and_findByName_should_work_with_permissions() {
		// given
		Permission p1 = permissionRepository.save(Permission.builder()
		                                                    .name("READ_TEST_" + UUID.randomUUID()).build());
		Permission p2 = permissionRepository.save(Permission.builder()
		                                                    .name("WRITE_TEST_" + UUID.randomUUID()).build());
		
		String roleName = "ROLE_TEST_" + UUID.randomUUID();
		Role role = Role.builder()
		                .name(roleName)
		                .permissions(Set.of(p1, p2))
		                .build();
		
		// when
		Role saved = roleRepository.save(role);
		
		// then
		assertThat(saved.getId()).isNotNull();
		
		var opt = roleRepository.findByName(roleName);
		assertThat(opt).isPresent();
		Role found = opt.get();
		assertThat(found.getName()).isEqualTo(roleName);
		assertThat(found.getPermissions()).hasSize(2);
		assertThat(found.getPermissions())
				.extracting(Permission::getId)
				.containsExactlyInAnyOrder(p1.getId(), p2.getId());
	}
	
	@Test
	void delete_role_should_remove_join_rows_but_keep_permissions() {
		// given
		Permission p = permissionRepository.save(Permission.builder()
		                                                   .name("ANY_" + UUID.randomUUID()).build());
		
		Role role = roleRepository.save(Role.builder()
		                                    .name("ROLE_DEL_" + UUID.randomUUID())
		                                    .permissions(Set.of(p))
		                                    .build());
		
		// when
		roleRepository.delete(role);
		
		// then
		assertThat(roleRepository.findById(role.getId())).isEmpty();
		// permission yaşamaya devam etmeli
		assertThat(permissionRepository.findById(p.getId())).isPresent();
	}
	
	@Test
	void save_role_without_permissions_should_be_ok() {
		// given
		String roleName = "ROLE_NOPERM_" + UUID.randomUUID();
		Role role = Role.builder().name(roleName).build();
		
		// when
		Role saved = roleRepository.save(role);
		
		// then
		assertThat(saved.getId()).isNotNull();
		assertThat(saved.getPermissions()).isNullOrEmpty();
		
		assertThat(roleRepository.findByName(roleName)).isPresent();
	}
}