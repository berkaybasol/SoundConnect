package com.berkayb.soundconnect.auth.controller;

import com.berkayb.soundconnect.auth.dto.request.GoogleAuthRequestDto;
import com.berkayb.soundconnect.auth.dto.response.LoginResponse;
import com.berkayb.soundconnect.shared.response.BaseResponse;

public interface GoogleAuthController {
	BaseResponse<LoginResponse> loginWithGoogle(GoogleAuthRequestDto googleAuthRequestDto);
}