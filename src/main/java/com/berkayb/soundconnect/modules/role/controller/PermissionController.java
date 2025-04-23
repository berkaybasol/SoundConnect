package com.berkayb.soundconnect.modules.role.controller;

import com.berkayb.soundconnect.modules.role.dto.request.PermissionRequest;
import com.berkayb.soundconnect.modules.role.dto.response.PermissionResponse;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

public interface PermissionController {
	ResponseEntity<BaseResponse<List<PermissionResponse>>> getAllPermissions();
	ResponseEntity<BaseResponse<PermissionResponse>> savePermission(PermissionRequest request);
	ResponseEntity<BaseResponse<Void>> deletePermission(UUID id);
	
}