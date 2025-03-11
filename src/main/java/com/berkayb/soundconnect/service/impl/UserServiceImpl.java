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

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements IUserService {
	private final UserRepository userRepository;
	private final UserMapper userMapper;
	private final InstrumentRepository instrumentRepository;
	
	
	@Override
	public Boolean updateUser(UserUpdateRequestDto dto) {
		User user = userRepository.findById(dto.id())
		                          .orElseThrow(() -> new RuntimeException("User bulunamadı!"));
		
		boolean isUpdated = false;
		
		if (dto.userName() != null) {
			user.setUserName(dto.userName());
			isUpdated = true;
		}
		if (dto.email() != null) {
			user.setEmail(dto.email());
			isUpdated = true;
		}
		if (dto.password() != null) {
			user.setPassword(dto.password()); // Şifre hashlenmeli!
			isUpdated = true;
		}
		if (dto.city() != null) {
			user.setCity(dto.city());
			isUpdated = true;
		}
		
		// Kullanıcının enstrümanlarını güncelle
		if (dto.instrumentIds() != null && !dto.instrumentIds().isEmpty()) {
			List<Instrument> instruments = instrumentRepository.findAllById(dto.instrumentIds());
			user.setInstruments(instruments);
			isUpdated = true;
		}
		
		if (isUpdated) {
			user.setUpdatedAt(LocalDateTime.now());
			userRepository.save(user);
		}
		
		return isUpdated;
	}
	
	@Override
	public void deleteUserById(Long id) {
		userRepository.deleteById(id);
	}
	
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
		User user = userMapper.toEntity(dto);
		if (dto.instrumentIds()!=null && !dto.instrumentIds().isEmpty()) {
			List<Instrument> instruments = instrumentRepository.findAllById(dto.instrumentIds());
			user.setInstruments(instruments);
		}
		return userRepository.save(user);
	}
	
}