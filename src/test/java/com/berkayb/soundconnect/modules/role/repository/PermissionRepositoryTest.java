package com.berkayb.soundconnect.modules.role.repository;

import com.berkayb.soundconnect.modules.role.entity.Permission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestConstructor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnableJpaRepositories(basePackages = "com.berkayb.soundconnect")
@EntityScan(basePackages = "com.berkayb.soundconnect")
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL) // <-- kritik
class PermissionRepositoryTest {
	
	private final PermissionRepository permissionRepository;
	
	PermissionRepositoryTest(PermissionRepository permissionRepository) {
		this.permissionRepository = permissionRepository;
	}
	
	@BeforeEach
	void clean() {
		permissionRepository.deleteAll();
	}
	
	@Test
	void findByName_should_return_permission_when_exists() {
		String name = "READ_X_" + UUID.randomUUID();
		Permission saved = permissionRepository.save(Permission.builder().name(name).build());
		
		var opt = permissionRepository.findByName(name);
		
		assertThat(opt).isPresent();
		assertThat(opt.get().getId()).isEqualTo(saved.getId());
		assertThat(opt.get().getName()).isEqualTo(name);
	}
	
	@Test
	void findByName_should_return_empty_when_not_exists() {
		var opt = permissionRepository.findByName("NOPE_" + UUID.randomUUID());
		assertThat(opt).isEmpty();
	}
	
	@Test
	void save_should_fail_on_duplicate_name() {
		String dup = "DUP_" + UUID.randomUUID();
		permissionRepository.saveAndFlush(Permission.builder().name(dup).build());
		
		assertThatThrownBy(() ->
				                   permissionRepository.saveAndFlush(Permission.builder().name(dup).build())
		).isInstanceOf(DataIntegrityViolationException.class);
	}
	
	@Test
	void delete_should_remove_entity() {
		var p = permissionRepository.save(Permission.builder().name("DEL_" + UUID.randomUUID()).build());
		assertThat(permissionRepository.findById(p.getId())).isPresent();
		
		permissionRepository.delete(p);
		
		assertThat(permissionRepository.findById(p.getId())).isEmpty();
	}
}