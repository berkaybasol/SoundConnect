package com.berkayb.soundconnect.modules.user.controller.admin;

import com.berkayb.soundconnect.modules.user.dto.request.UserSaveRequestDto;
import com.berkayb.soundconnect.modules.user.dto.request.UserUpdateRequestDto;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import com.berkayb.soundconnect.modules.user.dto.response.UserListDto;
import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

public interface UserController {
	ResponseEntity<BaseResponse<Boolean>> saveUser(UserDetailsImpl principal, UserSaveRequestDto dto);
	ResponseEntity<BaseResponse<List<UserListDto>>> getAllUsers();
	ResponseEntity<BaseResponse<UserListDto>> getUserById(UUID id);
	ResponseEntity<BaseResponse<Boolean>> deleteUserById(UserDetailsImpl principal, UUID id);
	ResponseEntity<BaseResponse<Boolean>> updateUser(UserDetailsImpl principal, UUID id, UserUpdateRequestDto dto);
}
