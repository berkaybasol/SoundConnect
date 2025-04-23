package com.berkayb.soundconnect.modules.role.service;

import com.berkayb.soundconnect.modules.role.dto.request.PermissionRequest;
import com.berkayb.soundconnect.modules.role.dto.response.PermissionResponse;

import java.util.List;
import java.util.UUID;

public interface PermissionService {
	List<PermissionResponse> findAllPermissions();
	PermissionResponse savePermission(PermissionRequest request);
	void deletePermission(UUID id);
}