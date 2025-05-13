package com.berkayb.soundconnect.auth.service;

import com.berkayb.soundconnect.auth.dto.request.LoginRequestDto;
import com.berkayb.soundconnect.auth.dto.request.RegisterRequestDto;
import com.berkayb.soundconnect.auth.dto.response.LoginResponse;
import com.berkayb.soundconnect.auth.security.JwtTokenProvider;
import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.role.enums.RoleEnum;
import com.berkayb.soundconnect.modules.role.repository.RoleRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AuthService {
	
	private final AuthenticationManager authenticationManager;
	private final JwtTokenProvider jwtTokenProvider;
	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;
	private final RoleRepository roleRepository;
	
	public BaseResponse<LoginResponse> login(LoginRequestDto request) {
		// kullaniciyi dogrula
		Authentication authentication = authenticationManager.authenticate(
				new UsernamePasswordAuthenticationToken(request.username(), request.password())
		);
		
		// dogrulanmis kullaniciyi al (UserDetailsImpl tipine downcast ederek)
		UserDetailsImpl userDetails = (UserDetailsImpl) authentication.getPrincipal();
		User user = userDetails.getUser();
		
		// Kullanıcının rolleri string olarak maplenir
		List<String> roles = user.getRoles().stream()
		                         .map(Role::getName)
		                         .toList();
		
		// dto hazirla
		LoginResponse response = new LoginResponse(
				user.getId().toString(),
				user.getUsername(),
				roles
		);
		
		// tokeni response sinifina sarip don
		return BaseResponse.<LoginResponse>builder()
		                   .success(true)
		                   .message("Login Successful")
		                   .code(200)
		                   .data(response)
		                   .build();
	}
	
	public BaseResponse<LoginResponse> register(RegisterRequestDto dto) {
		if (userRepository.existsByUsername(dto.username())) {
			throw new SoundConnectException(ErrorType.USER_ALREADY_EXISTS);
		}
		
		String encodedPassword = passwordEncoder.encode(dto.password());
		
		Role defaultRole = roleRepository.findByName(RoleEnum.ROLE_USER.name())
		                                 .orElseThrow(() -> new SoundConnectException(ErrorType.ROLE_NOT_FOUND));
		
		User user = User.builder()
		                .username(dto.username())
		                .email(dto.email())
		                .phone(dto.phone())
		                .gender(dto.gender())
		                .city(dto.city())
		                .roles(Set.of(defaultRole))
		                .password(encodedPassword)
		                .status(UserStatus.ACTIVE)
		                .createdAt(LocalDateTime.now())
		                .build();
		
		userRepository.save(user);
		
		// Roller string listesi haline getirilir
		List<String> roles = user.getRoles().stream()
		                         .map(Role::getName)
		                         .toList();
		
		// LoginResponse oluşturulur
		LoginResponse response = new LoginResponse(
				user.getId().toString(),
				user.getUsername(),
				roles
		);
		
		return BaseResponse.<LoginResponse>builder()
		                   .success(true)
		                   .message("Registration Successful")
		                   .code(201)
		                   .data(response)
		                   .build();
	}
	
	
}