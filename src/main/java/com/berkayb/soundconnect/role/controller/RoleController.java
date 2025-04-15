package com.berkayb.soundconnect.role.controller;

import com.berkayb.soundconnect.role.dto.request.RoleRequest;
import com.berkayb.soundconnect.role.dto.response.RoleResponse;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import org.springframework.http.ResponseEntity;

import java.util.List;

public interface RoleController {
	ResponseEntity<BaseResponse<List<RoleResponse>>> getAllRoles();
	ResponseEntity<BaseResponse<RoleResponse>> saveRole(RoleRequest request);
	ResponseEntity<BaseResponse<Void>> deleteRole(Long id);
}