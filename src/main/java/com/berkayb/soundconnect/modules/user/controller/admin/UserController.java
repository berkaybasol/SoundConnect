package com.berkayb.soundconnect.modules.user.controller.admin;

import com.berkayb.soundconnect.modules.user.dto.request.UserSaveRequestDto;
import com.berkayb.soundconnect.modules.user.dto.request.UserUpdateRequestDto;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import com.berkayb.soundconnect.modules.user.dto.response.UserListDto;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

public interface UserController {
	ResponseEntity<BaseResponse<Boolean>> saveUser(UserSaveRequestDto dto);
	ResponseEntity<BaseResponse<List<UserListDto>>> getAllUsers();
	ResponseEntity<BaseResponse<UserListDto>> getUserById(UUID id);
	ResponseEntity<BaseResponse<Boolean>> deleteUserById(UUID id);
	ResponseEntity<BaseResponse<Boolean>> updateUser(UserUpdateRequestDto dto);
}