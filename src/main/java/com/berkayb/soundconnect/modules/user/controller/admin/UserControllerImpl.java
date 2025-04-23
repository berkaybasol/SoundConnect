package com.berkayb.soundconnect.modules.user.controller.admin;

import com.berkayb.soundconnect.modules.user.dto.request.UserSaveRequestDto;
import com.berkayb.soundconnect.modules.user.dto.request.UserUpdateRequestDto;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import com.berkayb.soundconnect.modules.user.dto.response.UserListDto;
import com.berkayb.soundconnect.modules.user.service.UserService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

import static com.berkayb.soundconnect.shared.constant.EndPoints.User.*;


@RestController
@RequestMapping(BASE)
@RequiredArgsConstructor
@Tag(name = "Admin / User", description = "User management endpoints")

public class UserControllerImpl implements UserController {
	
	private final UserService userService;
	
	@PreAuthorize("hasAuthority('WRITE_USER')") // kullanıcı güncelleme yetkisi
	@PutMapping(UPDATE)
	@Override
	public ResponseEntity<BaseResponse<Boolean>> updateUser(@RequestBody UserUpdateRequestDto dto) {
		boolean isUpdated = userService.updateUser(dto);
		return ResponseEntity.ok(BaseResponse.<Boolean>builder()
		                                     .code(200)
		                                     .data(isUpdated)
		                                     .message(isUpdated ? "User başarıyla güncellendi." : "User güncellenemedi.")
		                                     .success(isUpdated)
		                                     .build());
	}
	
	@PreAuthorize("hasAuthority('DELETE_USER')")
	@DeleteMapping(DELETE)
	@Override
	public ResponseEntity<BaseResponse<Boolean>> deleteUserById(@PathVariable UUID id) {
		userService.deleteUserById(id);
		return ResponseEntity.ok(BaseResponse.<Boolean>builder()
		                                     .data(true)
				                         .code(200)
				                         .message("User deleted successfully")
				                         .success(true)
				                             .build());
	}
	
	
	@PreAuthorize("hasAuthority('READ_ALL_USERS')") // tüm kullanıcıları listeleme yetkisi
	@GetMapping(GET_ALL)
	@Override
	public ResponseEntity<BaseResponse<List<UserListDto>>> getAllUsers() {
		List<UserListDto> users = userService.getAllUsers();
		
		return ResponseEntity.ok(BaseResponse.<List<UserListDto>>builder()
		                                     .code(200)
		                                     .data(users)
		                                     .message("User Listesi getirildi.")
		                                     .success(true)
		                                     .build());
	}
	
	@PreAuthorize("hasAuthority('WRITE_USER')") // yeni kullanıcı kaydetme yetkisi
	@PostMapping(SAVE)
	@Override
	public ResponseEntity<BaseResponse<Boolean>> saveUser(@RequestBody UserSaveRequestDto dto) {
		userService.saveUser(dto);
		return ResponseEntity.ok(BaseResponse.<Boolean>builder()
				                         .code(200)
				                         .data(true)
				                         .message("User kaydedildi")
				                         .success(true)
				                             .build());
	}
	@PreAuthorize("hasAuthority('READ_USER')") // spesifik user'ı görme yetkisi
	@GetMapping(BY_ID)
	@Override
	public ResponseEntity<BaseResponse<UserListDto>> getUserById(@PathVariable UUID id) {
		return ResponseEntity.ok(BaseResponse.<UserListDto>builder()
		                                     .code(200)
		                                     .data(userService.getUserById(id))
		                                     .message("User getirildi.")
		                                     .success(true)
		                                     .build());
	}
}