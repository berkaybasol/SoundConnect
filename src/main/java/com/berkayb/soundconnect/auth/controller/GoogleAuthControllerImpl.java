package com.berkayb.soundconnect.auth.controller;

import com.berkayb.soundconnect.auth.dto.request.GoogleAuthRequestDto;
import com.berkayb.soundconnect.auth.dto.response.LoginResponse;
import com.berkayb.soundconnect.auth.service.GoogleAuthService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.berkayb.soundconnect.shared.constant.EndPoints.Auth.*;

@RestController
@RequestMapping(BASE)
@RequiredArgsConstructor
@Tag(name = "FOR USERS / Google Auth Controller", description = "Login procedures with Google")
public class GoogleAuthControllerImpl implements GoogleAuthController {
	private final GoogleAuthService googleAuthService;
	
	@Override
	@PostMapping(GOOGLE_SIGN_IN)
	public BaseResponse<LoginResponse> loginWithGoogle(@RequestBody @Valid GoogleAuthRequestDto googleAuthRequestDto) {
		return googleAuthService.loginWithGoogle(googleAuthRequestDto);
	}
}