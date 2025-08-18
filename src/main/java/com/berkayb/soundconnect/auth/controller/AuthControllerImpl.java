package com.berkayb.soundconnect.auth.controller;

import com.berkayb.soundconnect.auth.dto.request.LoginRequestDto;
import com.berkayb.soundconnect.auth.dto.request.RegisterRequestDto;
import com.berkayb.soundconnect.auth.dto.response.LoginResponse;
import com.berkayb.soundconnect.auth.dto.response.RegisterResponseDto;
import com.berkayb.soundconnect.auth.otp.dto.request.ResendCodeRequestDto;
import com.berkayb.soundconnect.auth.otp.dto.request.VerifyCodeRequestDto;
import com.berkayb.soundconnect.auth.otp.dto.response.ResendCodeResponseDto;
import com.berkayb.soundconnect.auth.service.AuthService;
import com.berkayb.soundconnect.shared.constant.EndPoints;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import static com.berkayb.soundconnect.shared.constant.EndPoints.Auth.*;

@RestController
@RequestMapping(BASE)
@RequiredArgsConstructor
@Tag(name = "FOR USERS / Auth Controller", description = "register, login includes transactions")
public class AuthControllerImpl implements AuthController {
	private final AuthService authService;
	
	
	@PostMapping(VERIFY_CODE)
	@Override
	public BaseResponse<Void> verifyEmail(@RequestBody @Valid VerifyCodeRequestDto dto) {
		return authService.verifyCode(dto);
	}
	
	
	@Override
	@PostMapping(REGISTER)
	public BaseResponse<RegisterResponseDto> register(@RequestBody @Valid RegisterRequestDto registerRequestDto) {
		return authService.register(registerRequestDto);
	}
	
	
	@Override
	@PostMapping(LOGIN)
	public BaseResponse<LoginResponse> login(@RequestBody @Valid LoginRequestDto loginRequestDto) {
		return authService.login(loginRequestDto);
	}
	
	
	@Override
	@PostMapping(RESEND_CODE)
	public BaseResponse<ResendCodeResponseDto> resendCode(@RequestBody @Valid ResendCodeRequestDto dto) {
		return authService.resendCode(dto);
	}
}