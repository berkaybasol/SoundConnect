package com.berkayb.soundconnect.auth.service;

import com.berkayb.soundconnect.auth.dto.request.GoogleAuthRequestDto;
import com.berkayb.soundconnect.auth.dto.request.GoogleCompleteProfileRequestDto;
import com.berkayb.soundconnect.auth.dto.response.LoginResponse;
import com.berkayb.soundconnect.auth.model.VerifiedGoogleIdentity;
import com.berkayb.soundconnect.auth.ratelimit.AuthAccountRateLimitGuard;
import com.berkayb.soundconnect.auth.security.GoogleIdTokenValidator;
import com.berkayb.soundconnect.modules.role.enums.RoleEnum;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class GoogleAuthServiceTest {
	@Test void bothPublicRequestsIndependentlyVerifyTheProviderBeforeTouchingAccounts() {
		var validator = mock(GoogleIdTokenValidator.class);
		var rateLimit = mock(AuthAccountRateLimitGuard.class);
		var accounts = mock(GoogleAccountService.class);
		var service = new GoogleAuthService(validator, rateLimit, accounts);
		var identity = new VerifiedGoogleIdentity("subject", "person@gmail.com", null, true);
		var form = new GoogleCompleteProfileRequestDto("fresh-proof", "musician", RoleEnum.ROLE_MUSICIAN, true);
		when(validator.verify("first-proof")).thenReturn(identity);
		when(validator.verify("fresh-proof")).thenReturn(identity);
		when(accounts.signIn(identity)).thenReturn(LoginResponse.googleProfileRequired());

		assertThat(service.loginWithGoogle(new GoogleAuthRequestDto("first-proof")).getData().token()).isNull();
		service.completeProfile(form);
		var order = inOrder(validator, rateLimit, accounts);
		order.verify(validator).verify("first-proof");
		order.verify(rateLimit).checkGoogleLogin("subject");
		order.verify(accounts).signIn(identity);
		order.verify(validator).verify("fresh-proof");
		order.verify(rateLimit).checkGoogleLogin("subject");
		order.verify(accounts).complete(identity, form);
	}
}
