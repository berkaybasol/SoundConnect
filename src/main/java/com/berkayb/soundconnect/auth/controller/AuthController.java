package com.berkayb.soundconnect.auth.controller;

import com.berkayb.soundconnect.auth.dto.request.LoginRequestDto;
import com.berkayb.soundconnect.auth.dto.request.RegisterRequestDto;
import com.berkayb.soundconnect.auth.dto.response.LoginResponse;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;

public interface AuthController {
	ResponseEntity<BaseResponse<LoginResponse>> login(LoginRequestDto loginRequestDto, HttpServletResponse response);
	ResponseEntity<BaseResponse<LoginResponse>> register(RegisterRequestDto request, HttpServletResponse response);
	
}