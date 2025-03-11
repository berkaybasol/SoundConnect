package com.berkayb.soundconnect.controller;

import com.berkayb.soundconnect.dto.request.UserSaveRequestDto;
import com.berkayb.soundconnect.dto.request.UserUpdateRequestDto;
import com.berkayb.soundconnect.dto.response.BaseResponse;
import com.berkayb.soundconnect.dto.response.UserListDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

import static com.berkayb.soundconnect.constant.EndPoints.UPDATE_USER;

public interface IUserController {
	ResponseEntity<BaseResponse<Boolean>> saveUser(UserSaveRequestDto dto);
	ResponseEntity<BaseResponse<List<UserListDto>>> getAllUsers();
	ResponseEntity<BaseResponse<UserListDto>> getUserById(Long id);
	ResponseEntity<BaseResponse<Boolean>> deleteUserById(Long id);
	ResponseEntity<BaseResponse<Boolean>> updateUser(UserUpdateRequestDto dto);
}