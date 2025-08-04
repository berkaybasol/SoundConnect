package com.berkayb.soundconnect.modules.role.controller;


import com.berkayb.soundconnect.modules.role.dto.request.PermissionRequest;
import com.berkayb.soundconnect.modules.role.dto.response.PermissionResponse;
import com.berkayb.soundconnect.modules.role.service.PermissionService;
import com.berkayb.soundconnect.modules.role.service.PermissionServiceImpl;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

import static com.berkayb.soundconnect.shared.constant.EndPoints.Permission.*;

@RestController
@RequestMapping(BASE)
@RequiredArgsConstructor
@Tag(name = "FOR ADMIN / Permission Controller ", description = "Permission management endpoints")
public class PermissionControllerImpl implements PermissionController {
	private final PermissionService permissionService;
	
	@PreAuthorize("hasAuthority('WRITE_PERMISSION')")
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
	@PreAuthorize("hasAuthority('DELETE_PERMISSION')")
	@DeleteMapping(DELETE)
	@Override
	public ResponseEntity<BaseResponse<Void>> deletePermission(@PathVariable UUID id) {
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
	@PreAuthorize("hasAuthority('READ_PERMISSION')")
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