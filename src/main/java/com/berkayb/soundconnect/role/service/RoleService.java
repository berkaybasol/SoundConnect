package com.berkayb.soundconnect.role.service;

import com.berkayb.soundconnect.role.dto.request.RoleRequest;
import com.berkayb.soundconnect.role.dto.response.RoleResponse;

import java.util.List;

public interface RoleService {
	List<RoleResponse> findAllRoles();
	
	RoleResponse saveRole(RoleRequest request);
	
	void deleteRole(Long id);
	
	
}