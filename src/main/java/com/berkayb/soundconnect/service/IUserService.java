package com.berkayb.soundconnect.service;

import com.berkayb.soundconnect.dto.request.UserSaveRequestDto;
import com.berkayb.soundconnect.dto.request.UserUpdateRequestDto;
import com.berkayb.soundconnect.dto.response.UserListDto;
import com.berkayb.soundconnect.entity.User;

import java.util.List;

public interface IUserService {
	User saveUser(UserSaveRequestDto dto);
	List<UserListDto> getAllUsers();
	UserListDto getUserById(Long id);
	void deleteUserById(Long id);
	Boolean updateUser(UserUpdateRequestDto dto);
	
}