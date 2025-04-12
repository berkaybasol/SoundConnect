package com.berkayb.soundconnect.user.controller;

import com.berkayb.soundconnect.user.dto.request.UserSaveRequestDto;
import com.berkayb.soundconnect.user.dto.request.UserUpdateRequestDto;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import com.berkayb.soundconnect.user.dto.response.UserListDto;
import org.springframework.http.ResponseEntity;

import java.util.List;

public interface IUserController {
	ResponseEntity<BaseResponse<Boolean>> saveUser(UserSaveRequestDto dto);
	ResponseEntity<BaseResponse<List<UserListDto>>> getAllUsers();
	ResponseEntity<BaseResponse<UserListDto>> getUserById(Long id);
	ResponseEntity<BaseResponse<Boolean>> deleteUserById(Long id);
	ResponseEntity<BaseResponse<Boolean>> updateUser(UserUpdateRequestDto dto);
}