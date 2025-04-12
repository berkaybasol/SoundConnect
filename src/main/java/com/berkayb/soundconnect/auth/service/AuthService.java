package com.berkayb.soundconnect.auth.service;

import com.berkayb.soundconnect.auth.security.JwtTokenProvider;
import com.berkayb.soundconnect.role.repository.RoleRepository;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import com.berkayb.soundconnect.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {
	
	private final AuthenticationManager authenticationManager;
	private final JwtTokenProvider jwtTokenProvider;
	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;
	private final RoleRepository roleRepository;
	
	
}