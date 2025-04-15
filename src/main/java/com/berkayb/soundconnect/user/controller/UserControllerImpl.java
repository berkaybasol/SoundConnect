package com.berkayb.soundconnect.user.controller;

import com.berkayb.soundconnect.user.dto.request.UserSaveRequestDto;
import com.berkayb.soundconnect.user.dto.request.UserUpdateRequestDto;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import com.berkayb.soundconnect.user.dto.response.UserListDto;
import com.berkayb.soundconnect.user.service.IUserService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.berkayb.soundconnect.shared.constant.EndPoints.User.*;


@RestController
@RequestMapping(BASE)
@RequiredArgsConstructor
@Tag(name = "User Controller", description = "user includes transactions")

public class UserControllerImpl implements IUserController {
	
	private final IUserService userService;
	
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
	
	
	@DeleteMapping(DELETE)
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
	
	@GetMapping(BY_ID)
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