package com.berkayb.soundconnect.modules.user.service;

import com.berkayb.soundconnect.modules.user.dto.request.UserSaveRequestDto;
import com.berkayb.soundconnect.modules.user.dto.request.UserUpdateRequestDto;
import com.berkayb.soundconnect.modules.user.dto.response.UserListDto;
import com.berkayb.soundconnect.modules.user.entity.User;

import java.util.List;
import java.util.UUID;

public interface UserService {
	User saveUser(UserSaveRequestDto dto);
	List<UserListDto> getAllUsers();
	UserListDto getUserById(UUID id);
	void deleteUserById(UUID id);
	Boolean updateUser(UserUpdateRequestDto dto);
	
}