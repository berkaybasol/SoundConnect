package com.berkayb.soundconnect.auth.controller;

import com.berkayb.soundconnect.auth.dto.request.LoginRequestDto;
import com.berkayb.soundconnect.auth.dto.request.RegisterRequestDto;
import com.berkayb.soundconnect.auth.dto.response.LoginResponse;
import com.berkayb.soundconnect.auth.service.AuthService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("auth")
@RequiredArgsConstructor
public class AuthControllerImpl implements AuthController {
	private final AuthService authService;
	
	
	@Override
	@GetMapping("/register")
	public BaseResponse<LoginResponse> register(@RequestBody @Valid RegisterRequestDto registerRequestDto) {
		return authService.register(registerRequestDto);
	}
	
	
	@Override
	@PostMapping("/login")
	public BaseResponse<LoginResponse> login(@RequestBody LoginRequestDto loginRequestDto) {
		return authService.login(loginRequestDto);
	}
}