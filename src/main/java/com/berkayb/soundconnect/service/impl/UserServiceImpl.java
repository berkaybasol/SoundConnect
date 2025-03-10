package com.berkayb.soundconnect.service.impl;

import com.berkayb.soundconnect.dto.request.UserSaveRequestDto;
import com.berkayb.soundconnect.dto.request.UserUpdateRequestDto;
import com.berkayb.soundconnect.dto.response.UserListDto;
import com.berkayb.soundconnect.entity.Instrument;
import com.berkayb.soundconnect.entity.User;
import com.berkayb.soundconnect.mapper.UserMapper;
import com.berkayb.soundconnect.repository.InstrumentRepository;
import com.berkayb.soundconnect.repository.UserRepository;
import com.berkayb.soundconnect.service.IUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements IUserService {
	private final UserRepository userRepository;
	private final UserMapper userMapper;
	
	
	@Override
	public UserListDto getUserById(Long id) {
		User user = userRepository.findById(id)
		                          .orElseThrow(() -> new RuntimeException("User not found"));
		return userMapper.toDto(user);
		
	}
	
	@Override
	public List<UserListDto> getAllUsers() {
		return userRepository.findAll()
				.stream()
				.map(userMapper::toDto)
				.collect(Collectors.toList());
	}
	
	
	@Override
	public User saveUser(UserSaveRequestDto dto) {
		return userRepository.save(userMapper.toEntity(dto));
	}
	
}