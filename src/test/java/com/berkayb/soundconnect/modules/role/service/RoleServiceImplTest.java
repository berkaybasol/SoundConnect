package com.berkayb.soundconnect.modules.role.service;

import com.berkayb.soundconnect.modules.role.dto.request.RoleRequest;
import com.berkayb.soundconnect.modules.role.dto.response.RoleResponse;
import com.berkayb.soundconnect.modules.role.dto.response.PermissionResponse;
import com.berkayb.soundconnect.modules.role.entity.Permission;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.role.mapper.RoleMapper;
import com.berkayb.soundconnect.modules.role.repository.PermissionRepository;
import com.berkayb.soundconnect.modules.role.repository.RoleRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RoleServiceImplTest {
	
	@Mock private PermissionRepository permissionRepository;
	@Mock private RoleRepository roleRepository;
	// >>> Değişiklik: gerçek MapStruct yerine MOCK
	@Mock private RoleMapper roleMapper;
	
	@InjectMocks
	private RoleServiceImpl sut;
	
	@BeforeEach
	void setup() {
		// @InjectMocks ctor’u kullanacak
		sut = new RoleServiceImpl(permissionRepository, roleRepository, roleMapper);
	}
	
	// ---------- saveRole ----------
	@Test
	void saveRole_ok() {
		UUID p1 = UUID.randomUUID();
		UUID p2 = UUID.randomUUID();
		
		RoleRequest req = RoleRequest.builder()
		                             .name("ROLE_NEON")
		                             .permissionIds(Set.of(p1, p2))
		                             .build();
		
		Permission perm1 = Permission.builder().id(p1).name("READ_X").build();
		Permission perm2 = Permission.builder().id(p2).name("WRITE_X").build();
		
		when(roleRepository.findByName("ROLE_NEON")).thenReturn(Optional.empty());
		when(permissionRepository.findAllById(req.permissionIds())).thenReturn(List.of(perm1, perm2));
		when(roleRepository.save(any(Role.class))).thenAnswer(inv -> {
			Role r = inv.getArgument(0);
			r.setId(UUID.randomUUID());
			return r;
		});
		
		// mapper stub: Role -> RoleResponse
		when(roleMapper.toDto(any(Role.class))).thenAnswer(inv -> {
			Role r = inv.getArgument(0);
			Set<PermissionResponse> perms = r.getPermissions().stream()
			                                 .map(p -> new PermissionResponse(p.getId(), p.getName()))
			                                 .collect(Collectors.toSet());
			return new RoleResponse(r.getId(), r.getName(), perms);
		});
		
		RoleResponse res = sut.saveRole(req);
		
		assertThat(res).isNotNull();
		assertThat(res.name()).isEqualTo("ROLE_NEON");
		assertThat(res.permissions()).hasSize(2);
		assertThat(res.permissions()).extracting(PermissionResponse::name)
		                             .containsExactlyInAnyOrder("READ_X", "WRITE_X");
		
		verify(roleRepository).findByName("ROLE_NEON");
		verify(permissionRepository).findAllById(req.permissionIds());
		verify(roleRepository).save(any(Role.class));
		verify(roleMapper).toDto(any(Role.class));
		verifyNoMoreInteractions(roleRepository, permissionRepository, roleMapper);
	}
	
	@Test
	void saveRole_throws_when_duplicate_name() {
		RoleRequest req = RoleRequest.builder()
		                             .name("ROLE_DUP")
		                             .permissionIds(Set.of(UUID.randomUUID()))
		                             .build();
		
		when(roleRepository.findByName("ROLE_DUP"))
				.thenReturn(Optional.of(Role.builder().id(UUID.randomUUID()).name("ROLE_DUP").build()));
		
		assertThatThrownBy(() -> sut.saveRole(req))
				.isInstanceOf(SoundConnectException.class)
				.hasMessageContaining(ErrorType.ROLE_ALREADY_EXISTS.getMessage());
		
		verify(roleRepository).findByName("ROLE_DUP");
		verifyNoMoreInteractions(roleRepository);
		verifyNoInteractions(permissionRepository, roleMapper);
	}
	
	@Test
	void saveRole_throws_when_some_permission_ids_invalid() {
		UUID valid = UUID.randomUUID();
		UUID invalid = UUID.randomUUID();
		
		RoleRequest req = RoleRequest.builder()
		                             .name("ROLE_BROKEN")
		                             .permissionIds(Set.of(valid, invalid))
		                             .build();
		
		when(roleRepository.findByName("ROLE_BROKEN")).thenReturn(Optional.empty());
		when(permissionRepository.findAllById(req.permissionIds()))
				.thenReturn(List.of(Permission.builder().id(valid).name("READ_OK").build()));
		
		assertThatThrownBy(() -> sut.saveRole(req))
				.isInstanceOf(SoundConnectException.class)
				.hasMessageContaining(ErrorType.PERMISSION_NOT_FOUND.getMessage());
		
		verify(roleRepository).findByName("ROLE_BROKEN");
		verify(permissionRepository).findAllById(req.permissionIds());
		verifyNoMoreInteractions(roleRepository, permissionRepository);
		verifyNoInteractions(roleMapper);
	}
	
	// ---------- deleteRole ----------
	@Test
	void deleteRole_ok() {
		UUID id = UUID.randomUUID();
		Role role = Role.builder().id(id).name("ROLE_DEL").build();
		
		when(roleRepository.findById(id)).thenReturn(Optional.of(role));
		doNothing().when(roleRepository).delete(role);
		
		sut.deleteRole(id);
		
		verify(roleRepository).findById(id);
		verify(roleRepository).delete(role);
		verifyNoMoreInteractions(roleRepository);
		verifyNoInteractions(permissionRepository, roleMapper);
	}
	
	@Test
	void deleteRole_throws_when_not_found() {
		UUID id = UUID.randomUUID();
		when(roleRepository.findById(id)).thenReturn(Optional.empty());
		
		assertThatThrownBy(() -> sut.deleteRole(id))
				.isInstanceOf(SoundConnectException.class)
				.hasMessageContaining(ErrorType.ROLE_NOT_FOUND.getMessage());
		
		verify(roleRepository).findById(id);
		verifyNoMoreInteractions(roleRepository);
		verifyNoInteractions(permissionRepository, roleMapper);
	}
	
	// ---------- findAllRoles ----------
	@Test
	void findAllRoles_ok() {
		Role r1 = Role.builder()
		              .id(UUID.randomUUID())
		              .name("ROLE_A")
		              .permissions(new HashSet<>(Set.of(Permission.builder().id(UUID.randomUUID()).name("READ_A").build())))
		              .build();
		
		Role r2 = Role.builder()
		              .id(UUID.randomUUID())
		              .name("ROLE_B")
		              .permissions(new HashSet<>(Set.of(Permission.builder().id(UUID.randomUUID()).name("WRITE_B").build())))
		              .build();
		
		when(roleRepository.findAll()).thenReturn(List.of(r1, r2));
		
		// mapper stub: list -> list
		when(roleMapper.toDtoList(anyList())).thenAnswer(inv -> {
			@SuppressWarnings("unchecked")
			List<Role> roles = (List<Role>) inv.getArgument(0);
			return roles.stream().map(r -> new RoleResponse(
					r.getId(),
					r.getName(),
					r.getPermissions().stream()
					 .map(p -> new PermissionResponse(p.getId(), p.getName()))
					 .collect(Collectors.toSet())
			)).toList();
		});
		
		var list = sut.findAllRoles();
		
		assertThat(list).hasSize(2);
		assertThat(list).extracting(RoleResponse::name)
		                .containsExactlyInAnyOrder("ROLE_A", "ROLE_B");
		assertThat(list.get(0).permissions()).isNotNull();
		assertThat(list.get(1).permissions()).isNotNull();
		
		verify(roleRepository).findAll();
		verify(roleMapper).toDtoList(anyList());
		verifyNoMoreInteractions(roleRepository, roleMapper);
		verifyNoInteractions(permissionRepository);
	}
	
	@Test
	void findAllRoles_throws_when_empty() {
		when(roleRepository.findAll()).thenReturn(Collections.emptyList());
		
		assertThatThrownBy(sut::findAllRoles)
				.isInstanceOf(SoundConnectException.class)
				.hasMessageContaining(ErrorType.ROLE_NOT_FOUND.getMessage());
		
		verify(roleRepository).findAll();
		verifyNoMoreInteractions(roleRepository);
		verifyNoInteractions(permissionRepository, roleMapper);
	}
}