package com.berkayb.soundconnect.role.service;

import com.berkayb.soundconnect.role.dto.request.RoleRequest;
import com.berkayb.soundconnect.role.dto.response.RoleResponse;
import com.berkayb.soundconnect.role.entity.Permission;
import com.berkayb.soundconnect.role.entity.Role;
import com.berkayb.soundconnect.role.mapper.RoleMapper;
import com.berkayb.soundconnect.role.repository.PermissionRepository;
import com.berkayb.soundconnect.role.repository.RoleRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.parameters.P;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RoleServiceImpl implements RoleService {
	private final PermissionRepository permissionRepository;
	private final RoleRepository roleRepository;
	private final RoleMapper roleMapper;
	
	
	@Override
	public RoleResponse saveRole(RoleRequest request) {
		log.info("save new role {}", request.name());
		
		roleRepository.findByName(request.name()).ifPresent(role -> {
			log.warn("role '{}' already exists", request.name());
			throw new SoundConnectException(ErrorType.ROLE_ALREADY_EXISTS);
		});
		
		List<Permission> permissions = permissionRepository.findAllById(request.permissionIds());
		
		if (permissions.size() != request.permissionIds().size()){
			log.error("some permissions IDs are invalid");
			throw new SoundConnectException(ErrorType.PERMISSION_NOT_FOUND);
		}
		Role role = Role.builder()
		                .name(request.name())
		                .permissions(new HashSet<>(permissions))
		                .build();
		
		Role saved = roleRepository.save(role);
		
		return roleMapper.toDto(saved);
	}
	
	@Override
	public void deleteRole(Long id) {
		log.info("Deleting role with id: {}", id);
		
		Role role = roleRepository.findById(id)
		                          .orElseThrow(() -> {
			                          log.warn("Role with id {} not found", id);
			                          return new SoundConnectException(ErrorType.ROLE_NOT_FOUND);
		                          });
		
		roleRepository.delete(role);
		
		log.info("Role with id {} deleted successfully", id);
	}
	
	@Override
	public List<RoleResponse> findAllRoles() {
		log.info("fetching all roles...");
		
		List<Role> roles = roleRepository.findAll();
		
		if (roles.isEmpty()) {
			log.info("no roles found");
			throw new SoundConnectException(ErrorType.ROLE_NOT_FOUND);
		}
		log.info("found {} roles", roles.size());
		
		return roleMapper.toDtoList(roles);
	}
	
	
	
}