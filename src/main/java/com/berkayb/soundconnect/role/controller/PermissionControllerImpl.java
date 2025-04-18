package com.berkayb.soundconnect.role.controller;


import com.berkayb.soundconnect.role.dto.request.PermissionRequest;
import com.berkayb.soundconnect.role.dto.response.PermissionResponse;
import com.berkayb.soundconnect.role.service.PermissionServiceImpl;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.berkayb.soundconnect.shared.constant.EndPoints.Permission.*;

@RestController
@RequestMapping(BASE)
@RequiredArgsConstructor
@Tag(name = "Permission Controller", description = "permission includes transactions")
public class PermissionControllerImpl implements PermissionController {
	private final PermissionServiceImpl permissionService;
	
	
	@PostMapping(SAVE)
	@Override
	public ResponseEntity<BaseResponse<PermissionResponse>> savePermission(@RequestBody @Valid PermissionRequest request) {
		PermissionResponse response = permissionService.savePermission(request);
		
		return ResponseEntity.ok(
				BaseResponse.<PermissionResponse>builder()
						.success(true)
						.message("permission created succesfully")
						.code(200)
						.data(response)
						.build()
		);
	}
	@DeleteMapping(DELETE)
	@Override
	public ResponseEntity<BaseResponse<Void>> deletePermission(@PathVariable Long id) {
		permissionService.deletePermission(id);
		return ResponseEntity.ok(
				BaseResponse.<Void>builder()
						.success(true)
						.message("permission deleted succesfully")
						.code(200)
						.data(null)
						.build()
		);
	}
	
	@GetMapping(GET_ALL)
	@Override
	public ResponseEntity<BaseResponse<List<PermissionResponse>>> getAllPermissions() {
		List<PermissionResponse> response = permissionService.findAllPermissions();
		
		return ResponseEntity.ok(BaseResponse.<List<PermissionResponse>>builder()
				                         .success(true)
				                         .message("permissions listed successfully.")
				                         .code(200)
				                         .data(response)
				                         .build());
				
	}
}