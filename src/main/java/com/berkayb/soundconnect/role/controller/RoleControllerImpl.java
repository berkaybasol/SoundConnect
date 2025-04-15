package com.berkayb.soundconnect.role.controller;

import com.berkayb.soundconnect.role.dto.request.RoleRequest;
import com.berkayb.soundconnect.role.dto.response.RoleResponse;
import com.berkayb.soundconnect.role.service.RoleService;
import com.berkayb.soundconnect.shared.constant.EndPoints;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

import static com.berkayb.soundconnect.shared.constant.EndPoints.Role.*;

@RestController
@RequestMapping(BASE)
@RequiredArgsConstructor
@Tag(name = "Role Controller", description = "role includes transactions")
public class RoleControllerImpl implements RoleController {
	private final RoleService roleService;
	
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
	
	@DeleteMapping(DELETE)
	@Override
	public ResponseEntity<BaseResponse<Void>> deleteRole(@PathVariable Long id) {
		roleService.deleteRole(id);
		return ResponseEntity.ok(BaseResponse.<Void>builder()
		                                     .success(true)
		                                     .message("Rol başarıyla silindi.")
		                                     .code(200)
		                                     .build());
	}
}