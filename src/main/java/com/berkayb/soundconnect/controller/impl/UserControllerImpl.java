package com.berkayb.soundconnect.controller.impl;

import com.berkayb.soundconnect.controller.IUserController;
import com.berkayb.soundconnect.dto.request.UserSaveRequestDto;
import com.berkayb.soundconnect.dto.response.BaseResponse;
import com.berkayb.soundconnect.dto.response.UserListDto;
import com.berkayb.soundconnect.entity.User;
import com.berkayb.soundconnect.service.IUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/rest/api/user")
@RequiredArgsConstructor

public class UserControllerImpl implements IUserController {
	private final IUserService userService;
	
	
	@GetMapping("/get-all-users")
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
	
	@PostMapping("/save-user")
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
	
	
}