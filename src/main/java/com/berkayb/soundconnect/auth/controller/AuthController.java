package com.berkayb.soundconnect.auth.controller;

import com.berkayb.soundconnect.auth.dto.request.LoginRequestDto;
import com.berkayb.soundconnect.auth.dto.request.RegisterRequestDto;
import com.berkayb.soundconnect.auth.dto.request.UsernameAvailabilityRequestDto;
import com.berkayb.soundconnect.auth.dto.response.LoginResponse;
import com.berkayb.soundconnect.auth.dto.response.RegisterResponseDto;
import com.berkayb.soundconnect.auth.dto.response.UsernameAvailabilityResponseDto;
import com.berkayb.soundconnect.auth.otp.dto.request.ResendCodeRequestDto;
import com.berkayb.soundconnect.auth.otp.dto.request.VerifyCodeRequestDto;
import com.berkayb.soundconnect.auth.otp.dto.response.ResendCodeResponseDto;
import com.berkayb.soundconnect.auth.passwordreset.dto.request.ForgotPasswordRequestDto;
import com.berkayb.soundconnect.auth.passwordreset.dto.request.ResetPasswordRequestDto;
import com.berkayb.soundconnect.auth.passwordreset.dto.response.PasswordResetAccountResponseDto;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import org.springframework.http.ResponseEntity;

public interface AuthController {
	ResponseEntity<BaseResponse<LoginResponse>> login(LoginRequestDto loginRequestDto);
	ResponseEntity<BaseResponse<RegisterResponseDto>> register(RegisterRequestDto registerRequestDto);
	ResponseEntity<BaseResponse<Void>> verifyEmail(VerifyCodeRequestDto dto);
	ResponseEntity<BaseResponse<ResendCodeResponseDto>> resendCode(ResendCodeRequestDto dto);
	ResponseEntity<BaseResponse<UsernameAvailabilityResponseDto>> usernameAvailability(
			UsernameAvailabilityRequestDto dto);
	ResponseEntity<BaseResponse<PasswordResetAccountResponseDto>> passwordResetAccount(
			ForgotPasswordRequestDto dto);
	ResponseEntity<BaseResponse<Void>> forgotPassword(ForgotPasswordRequestDto dto);
	ResponseEntity<BaseResponse<Void>> resetPassword(ResetPasswordRequestDto dto);
}
