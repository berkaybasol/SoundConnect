package com.berkayb.soundconnect.auth.security;

import com.berkayb.soundconnect.auth.model.VerifiedGoogleIdentity;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.util.EmailUtils;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Google ID token dogrulamasini tek noktada toplar. Verifier bir kez olusturulur;
 * ham token ve token parcasi hicbir log kaydina yazilmaz.
 */
@Component
@Slf4j
public class GoogleIdTokenValidator {

	private final GoogleIdTokenVerifier verifier;
	private final String googleClientId;

	public GoogleIdTokenValidator(
			@Value("${spring.security.oauth2.client.registration.google.client-id}") String googleClientId
	) {
		if (!StringUtils.hasText(googleClientId) || !googleClientId.equals(googleClientId.trim())) {
			throw new IllegalStateException("Google OAuth client ID must not be blank");
		}
		this.googleClientId = googleClientId;
		this.verifier = new GoogleIdTokenVerifier.Builder(
				new NetHttpTransport(),
				JacksonFactory.getDefaultInstance()
		)
				.setAudience(List.of(googleClientId))
				.build();
	}

	public VerifiedGoogleIdentity verify(String rawIdToken) {
		if (rawIdToken == null || rawIdToken.isBlank()) {
			throw unauthorizedGoogleToken();
		}

		final GoogleIdToken idToken;
		try {
			idToken = verifier.verify(rawIdToken);
		} catch (Exception exception) {
			log.warn("Google ID token verification failed: {}", exception.getClass().getSimpleName());
			throw unauthorizedGoogleToken();
		}

		if (idToken == null) {
			throw unauthorizedGoogleToken();
		}

		GoogleIdToken.Payload payload = idToken.getPayload();
		Object authorizedParty = payload.get("azp");
		if (authorizedParty != null && !googleClientId.equals(authorizedParty.toString())) {
			throw unauthorizedGoogleToken();
		}
		String subject = payload.getSubject();
		String email = EmailUtils.normalize(payload.getEmail());
		if (!isValidSubject(subject)) {
			throw unauthorizedGoogleToken();
		}
		if (!isPlausibleEmail(email) || !Boolean.TRUE.equals(payload.getEmailVerified())) {
			throw new SoundConnectException(
					ErrorType.UNAUTHORIZED,
					List.of("Google hesabi icin dogrulanmis bir e-posta adresi gerekli.")
			);
		}

		Object displayName = payload.get("name");
		return new VerifiedGoogleIdentity(
				subject,
				email,
				displayName instanceof String name ? name : null
		);
	}

	static boolean isValidSubject(String subject) {
		return subject != null
				&& subject.matches("[A-Za-z0-9._:-]{1,255}");
	}

	static boolean isPlausibleEmail(String email) {
		int separator = email.indexOf('@');
		return email.length() <= 254
				&& separator > 0
				&& separator == email.lastIndexOf('@')
				&& separator < email.length() - 1;
	}

	private SoundConnectException unauthorizedGoogleToken() {
		return new SoundConnectException(
				ErrorType.UNAUTHORIZED,
				List.of("Google ID token dogrulanamadi.")
		);
	}
}
