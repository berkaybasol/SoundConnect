package com.berkayb.soundconnect.user.controller;

import com.berkayb.soundconnect.user.dto.request.UserSaveRequestDto;
import com.berkayb.soundconnect.user.dto.request.UserUpdateRequestDto;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import com.berkayb.soundconnect.user.dto.response.UserListDto;
import com.berkayb.soundconnect.user.service.IUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;


@RestController
@RequestMapping(USERS)
@RequiredArgsConstructor


public class UserController implements IUserController {
	
	private final IUserService userService;
	
	@PutMapping(UPDATE_USER)
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
	
	
	@DeleteMapping(DELETE_USER)
	@Override
	public ResponseEntity<BaseResponse<Boolean>> deleteUserById(@PathVariable Long id) {
		userService.deleteUserById(id);
		return ResponseEntity.ok(BaseResponse.<Boolean>builder()
		                                     .data(true)
				                         .code(200)
				                         .message("User deleted successfully")
				                         .success(true)
				                             .build());
	}
	
	
	@GetMapping(GET_ALL_USERS)
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
	
	@PostMapping(SAVE_USER)
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
	
	@GetMapping(GET_USER_BY_ID)
	@Override
	public ResponseEntity<BaseResponse<UserListDto>> getUserById(@PathVariable Long id) {
		return ResponseEntity.ok(BaseResponse.<UserListDto>builder()
		                                     .code(200)
		                                     .data(userService.getUserById(id))
		                                     .message("User getirildi.")
		                                     .success(true)
		                                     .build());
	}
	
	
	
}