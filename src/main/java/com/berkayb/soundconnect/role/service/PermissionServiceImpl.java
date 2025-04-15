package com.berkayb.soundconnect.role.service;

import com.berkayb.soundconnect.role.dto.request.PermissionRequest;
import com.berkayb.soundconnect.role.dto.response.PermissionResponse;
import com.berkayb.soundconnect.role.entity.Permission;
import com.berkayb.soundconnect.role.mapper.PermissionMapper;
import com.berkayb.soundconnect.role.repository.PermissionRepository;
import com.berkayb.soundconnect.role.service.PermissionService;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PermissionServiceImpl implements PermissionService {
	private final PermissionRepository permissionRepository;
	private final PermissionMapper permissionMapper;
	
	@Override
	public void deletePermission(Long id) {
		log.info("Delete permission with id {}", id);
		
		var permission = permissionRepository.findById(id)
		                                     .orElseThrow(() -> {
												 log.warn("Permission with id {} not found", id);
												 return new SoundConnectException(ErrorType.PERMISSION_NOT_FOUND);
		                                     });
		permissionRepository.delete(permission);
		
		log.info("Permission with id {} deleted", id);
		
	}
	
	@Override
	public PermissionResponse savePermission(PermissionRequest request) {
		log.info("Saving new permission: {}", request.name());
		
		permissionRepository.findByName(request.name()).ifPresent(permission -> {
			log.warn("Permission with name {} already exists", request.name());
			throw new SoundConnectException(ErrorType.PERMISSION_ALREADY_EXISTS);
		});
		
		Permission permission = Permission.builder()
		                                  .name(request.name())
		                                  .build();
		
		Permission saved = permissionRepository.save(permission);
		
		log.info("Permission saved with id: {}", saved.getId());
		
		return permissionMapper.toDto(saved);
	}
	
	@Override
	public List<PermissionResponse> findAllPermissions() {
		log.info("fetching all permissions");
		
		List<Permission> permissions = permissionRepository.findAll();
		
		if (permissions.isEmpty()) {
			log.warn("no permissions found in the system");
			throw new SoundConnectException(ErrorType.PERMISSION_NOT_FOUND);
		}
		
		log.info("found {} permissions found", permissions.size());
		
		return permissionMapper.toDtoList(permissions);
	}
}