package com.berkayb.soundconnect.role.service;

import com.berkayb.soundconnect.role.dto.request.PermissionRequest;
import com.berkayb.soundconnect.role.dto.response.PermissionResponse;

import java.util.List;

public interface PermissionService {
	List<PermissionResponse> findAllPermissions();
	PermissionResponse savePermission(PermissionRequest request);
	void deletePermission(Long id);
}