package com.berkayb.soundconnect.service;

import com.berkayb.soundconnect.dto.request.UserSaveRequestDto;
import com.berkayb.soundconnect.dto.response.BaseResponse;
import com.berkayb.soundconnect.dto.response.UserListDto;
import com.berkayb.soundconnect.entity.User;
import org.springframework.http.ResponseEntity;

import java.util.List;

public interface IUserService {
	User saveUser(UserSaveRequestDto dto);
	List<UserListDto> getAllUsers();
}