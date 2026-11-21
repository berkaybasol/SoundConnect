package com.berkayb.soundconnect.auth.controller;

import com.berkayb.soundconnect.auth.dto.request.GoogleCompleteProfileRequestDto;
import com.berkayb.soundconnect.auth.dto.response.LoginResponse;
import com.berkayb.soundconnect.auth.service.GoogleAuthService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.berkayb.soundconnect.shared.constant.EndPoints.Auth.BASE;
import static com.berkayb.soundconnect.shared.constant.EndPoints.Auth.COMPLETE_GOOGLE_PROFILE;

@RestController
@RequestMapping(BASE)
@RequiredArgsConstructor
public class GoogleCompleteProfileController {
	private final GoogleAuthService googleAuthService;

	@PostMapping(COMPLETE_GOOGLE_PROFILE)
	public BaseResponse<LoginResponse> completeProfile(@Valid @RequestBody GoogleCompleteProfileRequestDto request) {
		LoginResponse result = googleAuthService.completeProfile(request);
		return BaseResponse.<LoginResponse>builder()
				.success(true).code(200).message(result.applicationId() == null ? "Profil tamamlandı." : "Başvuru durumu alındı.")
				.data(result).build();
	}
}
