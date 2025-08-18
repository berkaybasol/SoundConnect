package com.berkayb.soundconnect.auth.controller;

import com.berkayb.soundconnect.auth.dto.request.LoginRequestDto;
import com.berkayb.soundconnect.auth.dto.request.RegisterRequestDto;
import com.berkayb.soundconnect.auth.dto.response.LoginResponse;
import com.berkayb.soundconnect.auth.dto.response.RegisterResponseDto;
import com.berkayb.soundconnect.auth.otp.dto.request.ResendCodeRequestDto;
import com.berkayb.soundconnect.auth.otp.dto.request.VerifyCodeRequestDto;
import com.berkayb.soundconnect.auth.otp.dto.response.ResendCodeResponseDto;
import com.berkayb.soundconnect.shared.response.BaseResponse;

public interface AuthController {
	BaseResponse<LoginResponse> login(LoginRequestDto loginRequestDto);
	BaseResponse<RegisterResponseDto> register(RegisterRequestDto registerRequestDto);
	BaseResponse<Void> verifyEmail(VerifyCodeRequestDto dto);
	BaseResponse<ResendCodeResponseDto> resendCode(ResendCodeRequestDto dto);
}