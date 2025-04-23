package com.berkayb.soundconnect.modules.role.service;

import com.berkayb.soundconnect.modules.role.dto.request.RoleRequest;
import com.berkayb.soundconnect.modules.role.dto.response.RoleResponse;

import java.util.List;
import java.util.UUID;

public interface RoleService {
	List<RoleResponse> findAllRoles();
	
	RoleResponse saveRole(RoleRequest request);
	
	void deleteRole(UUID id);
	
	
}