package com.berkayb.soundconnect.modules.user.service;

import com.berkayb.soundconnect.modules.location.repository.CityRepository;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.role.repository.RoleRepository;
import com.berkayb.soundconnect.modules.user.dto.request.UserSaveRequestDto;
import com.berkayb.soundconnect.modules.user.dto.request.UserUpdateRequestDto;
import com.berkayb.soundconnect.modules.user.dto.response.UserListDto;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.user.mapper.UserMapper;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {
	
	private final UserRepository userRepository;
	private final RoleRepository roleRepository;
	private final UserMapper userMapper;
	private final UserEntityFinder userEntityFinder;
	private final PasswordEncoder passwordEncoder;
	private final CityRepository cityRepository;
	
	// kullaniciyi guncellerken yalnizca dolu gelen alanlari degistiriyoruz
	@Override
	public Boolean updateUser(UUID id, UserUpdateRequestDto dto) {
		// kullanici bulunamazsa hata firlatilir
		User user = userEntityFinder.getUser(id);
		
		boolean isUpdated = false;
		
		// kullanici adi guncellenirse flag true yapilir
		if (dto.username() != null) {
			user.setUsername(dto.username());
			isUpdated = true;
		}
		
		// eposta guncellenirse flag true
		if (dto.email() != null) {
			user.setEmail(dto.email());
			isUpdated = true;
		}
		
		// sifre guncellenirse hashlenerek set edilir
		if (dto.password() != null) {
			user.setPassword(passwordEncoder.encode(dto.password()));
			isUpdated = true;
		}
		
		// ROLE GÜNCELLEME: önce null check
		if (dto.roleId() != null) {
			Role role = roleRepository.findById(dto.roleId())
			                          .orElseThrow(() -> new SoundConnectException(ErrorType.ROLE_NOT_FOUND));
			user.getRoles().clear();
			user.getRoles().add(role);
			isUpdated = true;
		}
		
		
		// herhangi bir alan guncellenmisse updatedAt set edilir ve kaydedilir
		if (isUpdated) {
			user.setUpdatedAt(LocalDateTime.now());
			userRepository.save(user);
		}
		
		return isUpdated;
	}
	
	// kullaniciyi id'ye gore siler
	@Override
	public void deleteUserById(UUID id) {
		User user = userEntityFinder.getUser(id);
		userRepository.delete(user);
	}
	
	// id'ye gore kullaniciyi getirir
	@Override
	public UserListDto getUserById(UUID id) {
		User user = userEntityFinder.getUser(id);
		return userMapper.toDto(user);
	}
	
	// tum kullanicilari listeler
	@Transactional(readOnly = true)
	@Override
	public List<UserListDto> getAllUsers() {
		return userRepository.findAll().stream()
		                     .map(userMapper::toDto)
		                     .toList();
	}
	
	// yeni kullanici kaydeder, varsa enstrumanlarini da ekler
	@Override
	public User saveUser(UserSaveRequestDto dto) {
		// role
		Role role = roleRepository.findById(dto.roleId())
		                          .orElseThrow(() -> new SoundConnectException(ErrorType.ROLE_NOT_FOUND));
		
		
		// elle user oluştur
		User user = User.builder()
		                .username(dto.username())
		                .email(dto.email())
		                .password(passwordEncoder.encode(dto.password()))
		                .roles(Set.of(role))
		                .status(UserStatus.ACTIVE)
		                .createdAt(LocalDateTime.now())
		                .build();
		
		return userRepository.save(user);
	}
}