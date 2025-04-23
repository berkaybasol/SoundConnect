package com.berkayb.soundconnect.modules.role.controller;

import com.berkayb.soundconnect.modules.role.dto.request.RoleRequest;
import com.berkayb.soundconnect.modules.role.dto.response.RoleResponse;
import com.berkayb.soundconnect.modules.role.service.RoleService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

import static com.berkayb.soundconnect.shared.constant.EndPoints.Role.*;

@RestController
@RequestMapping(BASE)
@RequiredArgsConstructor
@Tag(name = "Role Controller", description = "Role management endpoints")
public class RoleControllerImpl implements RoleController {
	private final RoleService roleService;
	
	
	@PreAuthorize("hasAuthority('READ_ROLE')")
	@GetMapping(GET_ALL)
	@Override
	public ResponseEntity<BaseResponse<List<RoleResponse>>> getAllRoles() {
		List<RoleResponse> response = roleService.findAllRoles();
		
		return ResponseEntity.ok(BaseResponse.<List<RoleResponse>>builder()
				                         .success(true)
				                         .message("roles listed succesfully")
				                         .code(200)
				                         .data(response)
				                         .build());
	}
	
	@PreAuthorize("hasAuthority('WRITE_ROLE')")
	@PutMapping(SAVE)
	@Override
	public ResponseEntity<BaseResponse<RoleResponse>> saveRole(@RequestBody RoleRequest request) {
		RoleResponse saveRole = roleService.saveRole(request);
		return ResponseEntity.ok(BaseResponse.<RoleResponse>builder()
				                         .success(true)
				                         .message("role created succesfully")
				                         .code(201)
				                         .data(saveRole)
				                         .build());
	}
	
	@PreAuthorize("hasAuthority('DELETE_ROLE')")
	@DeleteMapping(DELETE)
	@Override
	public ResponseEntity<BaseResponse<Void>> deleteRole(@PathVariable UUID id) {
		roleService.deleteRole(id);
		return ResponseEntity.ok(BaseResponse.<Void>builder()
		                                     .success(true)
		                                     .message("Rol başarıyla silindi.")
		                                     .code(200)
		                                     .build());
	}
}