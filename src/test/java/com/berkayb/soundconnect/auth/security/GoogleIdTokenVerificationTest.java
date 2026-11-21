package com.berkayb.soundconnect.auth.security;

import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.googleapis.auth.oauth2.GooglePublicKeysManager;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.json.webtoken.JsonWebSignature;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GoogleIdTokenVerificationTest {
	private static final JacksonFactory JSON = JacksonFactory.getDefaultInstance();
	private static final String WEB = "web.apps.googleusercontent.com";
	private static final String ANDROID = "android.apps.googleusercontent.com";
	private static KeyPair signingKey;
	private GooglePublicKeysManager publicKeys;
	private GoogleIdTokenValidator validator;

	@BeforeAll static void generateFixtureKey() throws Exception {
		signingKey = KeyPairGenerator.getInstance("RSA").generateKeyPair();
	}

	@BeforeEach void configureLocalPublicKey() throws Exception {
		publicKeys = mock(GooglePublicKeysManager.class);
		when(publicKeys.getJsonFactory()).thenReturn(JSON);
		when(publicKeys.getPublicKeys()).thenReturn(List.of(signingKey.getPublic()));
		validator = new GoogleIdTokenValidator(WEB, ANDROID, new GoogleIdTokenVerifier.Builder(publicKeys));
	}

	@Test void verifiesSignatureAndAcceptsWebAudienceWithRegisteredAndroidPresenter() throws Exception {
		var payload = payload().setAuthorizedParty(ANDROID);
		var result = validator.verify(sign(payload));
		assertThat(result.subject()).isEqualTo("stable-id");
		assertThat(result.authoritativeEmail()).isTrue();
		assertThat(validator.verify(sign(payload().setAuthorizedParty(WEB))).subject()).isEqualTo("stable-id");
		assertThat(validator.verify(sign(payload())).subject()).isEqualTo("stable-id");
	}

	@Test void nativePresenterCannotReplaceTheAudienceAndUnsignedClaimsDoNotAuthorizeAnything() throws Exception {
		rejected(sign(payload().setAudience(ANDROID).setAuthorizedParty(ANDROID)), ErrorType.UNAUTHORIZED);
		rejected(sign(payload().setAudience(123)), ErrorType.UNAUTHORIZED);
		rejected(sign(payload().setAudience(List.of())), ErrorType.UNAUTHORIZED);
		rejected(sign(payload().setAuthorizedParty("unknown-presenter")), ErrorType.UNAUTHORIZED);
		rejected(sign(payload().setIssuedAtTimeSeconds(null)), ErrorType.UNAUTHORIZED);
		rejected(sign(payload().setExpirationTimeSeconds(null)), ErrorType.UNAUTHORIZED);
		rejected(sign(payload().setIssuer("wrong-issuer")), ErrorType.UNAUTHORIZED);
		rejected(sign(payload().setExpirationTimeSeconds(Instant.now().minusSeconds(1800).getEpochSecond())), ErrorType.UNAUTHORIZED);
		rejected(sign(payload().setEmailVerified(false)), ErrorType.UNAUTHORIZED);
		rejected("malformed", ErrorType.UNAUTHORIZED);
		when(publicKeys.getPublicKeys()).thenReturn(List.of(KeyPairGenerator.getInstance("RSA").generateKeyPair().getPublic()));
		rejected(sign(payload()), ErrorType.UNAUTHORIZED);
	}

	@Test void distinguishesThirdPartyMailFromGoogleHostedMail() throws Exception {
		assertThat(validator.verify(sign(payload().setEmail("someone@example.invalid"))).authoritativeEmail()).isFalse();
		assertThat(validator.verify(sign(payload().setEmail("someone@example.invalid").setHostedDomain("example.invalid"))).authoritativeEmail()).isTrue();
	}

	@Test void keyProviderOutageReturnsRetryableFailure() throws Exception {
		when(publicKeys.getPublicKeys()).thenThrow(new IOException("unavailable"));
		rejected(sign(payload()), ErrorType.GOOGLE_SIGN_IN_UNAVAILABLE);
	}

	private static GoogleIdToken.Payload payload() {
		long now = Instant.now().getEpochSecond();
		return new GoogleIdToken.Payload().setIssuer("https://accounts.google.com").setAudience(WEB)
				.setSubject("stable-id").setIssuedAtTimeSeconds(now).setExpirationTimeSeconds(now + 3600)
				.setEmail("someone@gmail.com").setEmailVerified(true);
	}

	private static String sign(GoogleIdToken.Payload payload) throws Exception {
		return JsonWebSignature.signUsingRsaSha256(signingKey.getPrivate(), JSON,
				new JsonWebSignature.Header().setAlgorithm("RS256"), payload);
	}

	private void rejected(String token, ErrorType expected) {
		assertThatThrownBy(() -> validator.verify(token)).isInstanceOfSatisfying(SoundConnectException.class,
				e -> assertThat(e.getErrorType()).isEqualTo(expected));
	}
}
