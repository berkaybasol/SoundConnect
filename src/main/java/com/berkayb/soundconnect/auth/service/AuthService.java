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
		
		// token uret
		String token = jwtTokenProvider.generateToken(userDetails);
		
		// tokeni response sinifina sarip don
		return BaseResponse.<LoginResponse>builder()
		                   .success(true)
		                   .message("Entry Successful")
		                   .code(200)
		                   .data(new LoginResponse(token))
		                   .build();
	}
	
	public BaseResponse<LoginResponse> register(RegisterRequestDto dto) {
		// Kullanici adi dahg once alinmis mi kontrol et
		if (userRepository.existsByUsername(dto.username())){
			throw new SoundConnectException(ErrorType.USER_ALREADY_EXISTS);
		}
		// sifreyi encode et
		String encodedPassword = passwordEncoder.encode(dto.password());
		
		// varsayilan rolu veritabanindan cek
		Role defaultRole = roleRepository.findByName(RoleEnum.ROLE_USER.name())
		                                 .orElseThrow(() -> new SoundConnectException(ErrorType.ROLE_NOT_FOUND));
		
		// yeni kullaniciyi olustur
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
		
		// veritabanina kaydet
		userRepository.save(user);
		
		// token uret
		UserDetailsImpl userDetails = new UserDetailsImpl(user);
		String token = jwtTokenProvider.generateToken(userDetails);
		
		// tokeni response sinifina sarip don
		return BaseResponse.<LoginResponse>builder()
		                   .success(true)
		                   .message("Registration Successful")
		                   .code(201)
		                   .data(new LoginResponse(token))
		                   .build();
		
	}
	
	
}