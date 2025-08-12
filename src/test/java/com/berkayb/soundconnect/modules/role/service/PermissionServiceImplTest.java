package com.berkayb.soundconnect.modules.role.service;

import com.berkayb.soundconnect.modules.role.dto.request.PermissionRequest;
import com.berkayb.soundconnect.modules.role.dto.response.PermissionResponse;
import com.berkayb.soundconnect.modules.role.entity.Permission;
import com.berkayb.soundconnect.modules.role.mapper.PermissionMapper;
import com.berkayb.soundconnect.modules.role.repository.PermissionRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@Tag("service")
class PermissionServiceImplTest {
	
	@Mock
	private PermissionRepository permissionRepository;
	
	// MapStruct gerçek implementasyon
	private PermissionMapper permissionMapper = Mappers.getMapper(PermissionMapper.class);
	
	@InjectMocks
	private PermissionServiceImpl sut; // system under test
	
	@BeforeEach
	void init() {
		// @InjectMocks mapper'ı otomatik enjekte etmez; o yüzden ctor ile manuel kuruyoruz.
		sut = new PermissionServiceImpl(permissionRepository, permissionMapper);
	}
	
	// ---- savePermission ----
	@Test
	void savePermission_ok() {
		// given
		var req = PermissionRequest.builder().name("READ_THING").build();
		
		when(permissionRepository.findByName("READ_THING")).thenReturn(Optional.empty());
		when(permissionRepository.save(any(Permission.class))).thenAnswer(inv -> {
			Permission p = inv.getArgument(0);
			p.setId(UUID.randomUUID());
			return p;
		});
		
		// when
		PermissionResponse res = sut.savePermission(req);
		
		// then
		assertThat(res).isNotNull();
		assertThat(res.name()).isEqualTo("READ_THING");
		assertThat(res.id()).isNotNull();
		
		verify(permissionRepository).findByName("READ_THING");
		verify(permissionRepository).save(any(Permission.class));
		verifyNoMoreInteractions(permissionRepository);
	}
	
	@Test
	void savePermission_throws_when_duplicate() {
		// given
		var req = PermissionRequest.builder().name("WRITE_THING").build();
		when(permissionRepository.findByName("WRITE_THING"))
				.thenReturn(Optional.of(Permission.builder().id(UUID.randomUUID()).name("WRITE_THING").build()));
		
		// when + then
		assertThatThrownBy(() -> sut.savePermission(req))
				.isInstanceOf(SoundConnectException.class)
				.hasMessageContaining(ErrorType.PERMISSION_ALREADY_EXISTS.getMessage());
		
		verify(permissionRepository).findByName("WRITE_THING");
		verifyNoMoreInteractions(permissionRepository);
	}
	
	// ---- findAllPermissions ----
	@Test
	void findAllPermissions_ok() {
		// given
		var p1 = Permission.builder().id(UUID.randomUUID()).name("A").build();
		var p2 = Permission.builder().id(UUID.randomUUID()).name("B").build();
		when(permissionRepository.findAll()).thenReturn(List.of(p1, p2));
		
		// when
		var list = sut.findAllPermissions();
		
		// then
		assertThat(list).hasSize(2);
		assertThat(list).extracting(PermissionResponse::name).containsExactlyInAnyOrder("A", "B");
		
		verify(permissionRepository).findAll();
		verifyNoMoreInteractions(permissionRepository);
	}
	
	@Test
	void findAllPermissions_throws_when_empty() {
		// given
		when(permissionRepository.findAll()).thenReturn(Collections.emptyList());
		
		// when + then
		assertThatThrownBy(() -> sut.findAllPermissions())
				.isInstanceOf(SoundConnectException.class)
				.hasMessageContaining(ErrorType.PERMISSION_NOT_FOUND.getMessage());
		
		verify(permissionRepository).findAll();
		verifyNoMoreInteractions(permissionRepository);
	}
	
	// ---- deletePermission ----
	@Test
	void deletePermission_ok() {
		UUID id = UUID.randomUUID();
		when(permissionRepository.findById(id))
				.thenReturn(Optional.of(Permission.builder().id(id).name("X").build()));
		
		// when
		sut.deletePermission(id);
		
		// then
		verify(permissionRepository).findById(id);
		verify(permissionRepository).delete(any(Permission.class));
		verifyNoMoreInteractions(permissionRepository);
	}
	
	@Test
	void deletePermission_throws_when_not_found() {
		UUID id = UUID.randomUUID();
		when(permissionRepository.findById(id)).thenReturn(Optional.empty());
		
		assertThatThrownBy(() -> sut.deletePermission(id))
				.isInstanceOf(SoundConnectException.class)
				.hasMessageContaining(ErrorType.PERMISSION_NOT_FOUND.getMessage());
		
		verify(permissionRepository).findById(id);
		verifyNoMoreInteractions(permissionRepository);
	}
}