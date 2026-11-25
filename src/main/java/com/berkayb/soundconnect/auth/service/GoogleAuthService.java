package com.berkayb.soundconnect.auth.service;

import com.berkayb.soundconnect.auth.dto.request.GoogleAuthRequestDto;
import com.berkayb.soundconnect.auth.dto.request.GoogleCompleteProfileRequestDto;
import com.berkayb.soundconnect.auth.dto.response.LoginResponse;
import com.berkayb.soundconnect.auth.model.VerifiedGoogleIdentity;
import com.berkayb.soundconnect.auth.ratelimit.AuthAccountRateLimitGuard;
import com.berkayb.soundconnect.auth.security.GoogleIdTokenValidator;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GoogleAuthService {
	private final GoogleIdTokenValidator validator;
	private final AuthAccountRateLimitGuard rateLimit;
	private final GoogleAccountService accounts;

	public BaseResponse<LoginResponse> loginWithGoogle(GoogleAuthRequestDto request) {
		LoginResponse result = accounts.signIn(verify(request.idToken()));
		return BaseResponse.<LoginResponse>builder().success(true).code(200)
				.message(result.requiresGoogleProfileCompletion() ? "Profil seçimi gerekiyor." : "Google kimliği doğrulandı.")
				.data(result).build();
	}

	public LoginResponse completeProfile(GoogleCompleteProfileRequestDto request) {
		return accounts.complete(verify(request.idToken()), request);
	}

	// Provider verification may refresh Google's public keys. Do it before starting a DB transaction.
	private VerifiedGoogleIdentity verify(String idToken) {
		VerifiedGoogleIdentity identity = validator.verify(idToken);
		rateLimit.checkGoogleLogin(identity.subject());
		return identity;
	}
}
