package com.berkayb.soundconnect.auth.controller;

import com.berkayb.soundconnect.auth.dto.request.GoogleCompleteProfileRequestDto;
import com.berkayb.soundconnect.auth.service.GoogleCompleteProfileService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.UUID;

import static com.berkayb.soundconnect.shared.constant.EndPoints.Auth.*;

@RestController
@RequestMapping(BASE)
@RequiredArgsConstructor
@Tag(name = "Google Complete Profile", description = "Google ile giren kullanıcılar için profil tamamlama endpointleri")
@Slf4j
public class GoogleCompleteProfileController {
	
	private final GoogleCompleteProfileService googleCompleteProfileService;
	
	/**
	 * Google ile giriş yapan kullanıcıya profil tamamlama imkanı sunar.
	 * Sadece eksik profil durumu varsa rol atanır ve ilgili profil açılır.
	 */
	
	@PostMapping(COMPLETE_GOOGLE_PROFILE)
	public ResponseEntity<BaseResponse<Void>> completeProfileWithRole(
			@AuthenticationPrincipal(expression = "user.id") UUID userId,
			@RequestBody GoogleCompleteProfileRequestDto dto
	) {
		log.info("Profil tamamlama isteği geldi. UserId: {}, Role: {}", userId, dto.role());
		googleCompleteProfileService.completeProfileWithRole(userId, dto);
		
		return ResponseEntity.ok(BaseResponse.<Void>builder()
		                                     .success(true)
		                                     .message("Profil başarıyla tamamlandı.")
		                                     .build());
	}
}