package com.berkayb.soundconnect.user.service;

import com.berkayb.soundconnect.user.dto.request.UserSaveRequestDto;
import com.berkayb.soundconnect.user.dto.request.UserUpdateRequestDto;
import com.berkayb.soundconnect.user.dto.response.UserListDto;
import com.berkayb.soundconnect.user.entity.User;

import java.util.List;

public interface IUserService {
	User saveUser(UserSaveRequestDto dto);
	List<UserListDto> getAllUsers();
	UserListDto getUserById(Long id);
	void deleteUserById(Long id);
	Boolean updateUser(UserUpdateRequestDto dto);
	
}