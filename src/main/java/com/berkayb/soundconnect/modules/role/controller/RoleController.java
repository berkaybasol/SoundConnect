package com.berkayb.soundconnect.modules.role.controller;

import com.berkayb.soundconnect.modules.role.dto.request.RoleRequest;
import com.berkayb.soundconnect.modules.role.dto.response.RoleResponse;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

public interface RoleController {
	ResponseEntity<BaseResponse<List<RoleResponse>>> getAllRoles();
	ResponseEntity<BaseResponse<RoleResponse>> saveRole(RoleRequest request);
	ResponseEntity<BaseResponse<Void>> deleteRole(UUID id);
}