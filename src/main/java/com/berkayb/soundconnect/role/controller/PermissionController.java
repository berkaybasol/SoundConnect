package com.berkayb.soundconnect.role.controller;

import com.berkayb.soundconnect.role.dto.request.PermissionRequest;
import com.berkayb.soundconnect.role.dto.response.PermissionResponse;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

public interface PermissionController {
	ResponseEntity<BaseResponse<List<PermissionResponse>>> getAllPermissions();
	ResponseEntity<BaseResponse<PermissionResponse>> savePermission(PermissionRequest request);
	ResponseEntity<BaseResponse<Void>> deletePermission(Long id);
	
}