package com.berkayb.soundconnect.controller.impl;

import com.berkayb.soundconnect.controller.IUserController;
import com.berkayb.soundconnect.dto.request.UserSaveRequestDto;
import com.berkayb.soundconnect.dto.request.UserUpdateRequestDto;
import com.berkayb.soundconnect.dto.response.BaseResponse;
import com.berkayb.soundconnect.dto.response.UserListDto;
import com.berkayb.soundconnect.service.IUserService;
import static com.berkayb.soundconnect.constant.EndPoints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;


@RestController
@RequestMapping(USERS)
@RequiredArgsConstructor


public class UserControllerImpl implements IUserController {
	private final IUserService userService;
	
	
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