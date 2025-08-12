package com.berkayb.soundconnect.modules.role.mapper;

import com.berkayb.soundconnect.modules.role.dto.response.PermissionResponse;
import com.berkayb.soundconnect.modules.role.entity.Permission;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
@Tag("mapper")
class PermissionMapperTest {
	
	private final PermissionMapper mapper = PermissionMapper.INSTANCE;
	
	@Test
	void toDto_should_map_id_and_name() {
		var p = Permission.builder().name("READ_X").build();
		var id = UUID.randomUUID();
		p.setId(id);
		
		PermissionResponse dto = mapper.toDto(p);
		
		assertThat(dto.id()).isEqualTo(id);
		assertThat(dto.name()).isEqualTo("READ_X");
	}
	
	@Test
	void toDtoList_should_map_list() {
		var p1 = Permission.builder().name("A").build(); p1.setId(UUID.randomUUID());
		var p2 = Permission.builder().name("B").build(); p2.setId(UUID.randomUUID());
		
		List<PermissionResponse> list = mapper.toDtoList(List.of(p1, p2));
		
		assertThat(list).hasSize(2);
		assertThat(list.get(0).name()).isEqualTo("A");
		assertThat(list.get(1).name()).isEqualTo("B");
	}
}