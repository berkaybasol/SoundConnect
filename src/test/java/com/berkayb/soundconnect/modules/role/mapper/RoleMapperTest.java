package com.berkayb.soundconnect.modules.role.mapper;

import com.berkayb.soundconnect.modules.role.dto.response.PermissionResponse;
import com.berkayb.soundconnect.modules.role.dto.response.RoleResponse;
import com.berkayb.soundconnect.modules.role.entity.Permission;
import com.berkayb.soundconnect.modules.role.entity.Role;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MapStruct mapper’larını hafif Spring context ile test ediyoruz.
 * Uygulama komple ayağa kalkmıyor; sadece Impl sınıfları yükleniyor.
 */
@SpringJUnitConfig(classes = { RoleMapperImpl.class, PermissionMapperImpl.class })
@Tag("mapper")
class RoleMapperTest {
	
	@Autowired RoleMapper roleMapper;
	
	@Test
	void toDto_should_map_role_and_nested_permissions() {
		var p1 = Permission.builder().name("READ_X_" + UUID.randomUUID()).build();
		p1.setId(UUID.randomUUID());
		var p2 = Permission.builder().name("WRITE_Y_" + UUID.randomUUID()).build();
		p2.setId(UUID.randomUUID());
		
		var roleName = "ROLE_SAMPLE_" + UUID.randomUUID();
		var role = Role.builder()
		               .name(roleName)
		               .permissions(Set.of(p1, p2))
		               .build();
		
		RoleResponse dto = roleMapper.toDto(role);
		
		assertThat(dto).isNotNull();
		assertThat(dto.name()).isEqualTo(roleName);
		assertThat(dto.permissions()).hasSize(2);
		assertThat(dto.permissions())
				.extracting(PermissionResponse::name)
				.containsExactlyInAnyOrder(p1.getName(), p2.getName());
	}
	
	@Test
	void toDtoList_should_map_list() {
		var p = Permission.builder().name("READ_ONLY").build();
		
		var r1 = Role.builder().name("ROLE_A").permissions(Set.of(p)).build();
		var r2 = Role.builder().name("ROLE_B").permissions(Set.of(p)).build();
		
		var listDto = roleMapper.toDtoList(java.util.List.of(r1, r2));
		
		assertThat(listDto).hasSize(2);
		assertThat(listDto).extracting(RoleResponse::name)
		                   .containsExactlyInAnyOrder("ROLE_A", "ROLE_B");
		assertThat(listDto.get(0).permissions()).hasSize(1);
	}
}