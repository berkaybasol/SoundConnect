package com.berkayb.soundconnect.controller;

import com.berkayb.soundconnect.dto.request.UserSaveRequestDto;
import com.berkayb.soundconnect.dto.response.BaseResponse;
import com.berkayb.soundconnect.dto.response.UserListDto;
import com.berkayb.soundconnect.entity.User;
import org.springframework.http.ResponseEntity;

import java.util.List;

public interface IUserController {
	public ResponseEntity<BaseResponse<Boolean>> saveUser(UserSaveRequestDto dto);
	public ResponseEntity<BaseResponse<List<UserListDto>>> getAllUsers();
	
}