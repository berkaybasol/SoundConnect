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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.SignatureException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Google ID token dogrulamasini tek noktada toplar. Verifier bir kez olusturulur;
 * ham token ve token parcasi hicbir log kaydina yazilmaz.
 */
@Component
@Slf4j
public class GoogleIdTokenValidator {

	private final GoogleIdTokenVerifier verifier;
	private final Set<String> presenters;
	private final boolean configured;

	@Autowired
	public GoogleIdTokenValidator(
			@Value("${app.google.audience:}") String googleClientId,
			@Value("${app.google.authorized-presenters:}") String nativePresenters
	) {
		this(googleClientId, nativePresenters, new GoogleIdTokenVerifier.Builder(
				new NetHttpTransport(), JacksonFactory.getDefaultInstance()));
	}

	GoogleIdTokenValidator(String audience, String nativePresenters, GoogleIdTokenVerifier.Builder builder) {
		configured = StringUtils.hasText(audience);
		verifier = builder.setAudience(List.of(audience)).build();
		Set<String> allowed = new HashSet<>();
		allowed.add(audience);
		Arrays.stream(nativePresenters.split(",")).map(String::trim).filter(StringUtils::hasText).forEach(allowed::add);
		presenters = Set.copyOf(allowed);
	}

	public VerifiedGoogleIdentity verify(String rawIdToken) {
		if (!configured) throw new SoundConnectException(ErrorType.GOOGLE_SIGN_IN_UNAVAILABLE);
		if (rawIdToken == null || rawIdToken.isBlank() || rawIdToken.length() > 16_384) {
			throw unauthorizedGoogleToken();
		}

		final GoogleIdToken idToken;
		try {
			idToken = GoogleIdToken.parse(verifier.getJsonFactory(), rawIdToken);
		} catch (IOException | IllegalArgumentException invalid) {
			throw unauthorizedGoogleToken();
		}
		GoogleIdToken.Payload payload = idToken.getPayload();
		if (!"RS256".equals(idToken.getHeader().getAlgorithm()) || !isValidAudienceClaim(payload.getAudience())
				|| payload.getIssuer() == null || payload.getIssuedAtTimeSeconds() == null
				|| payload.getExpirationTimeSeconds() == null) {
			throw unauthorizedGoogleToken();
		}
		try {
			if (!verifier.verify(idToken)) throw unauthorizedGoogleToken();
		} catch (IllegalArgumentException | SignatureException invalid) {
			throw unauthorizedGoogleToken();
		} catch (IOException | GeneralSecurityException unavailable) {
			log.warn("Google verification keys unavailable. cause={}", unavailable.getClass().getSimpleName());
			throw new SoundConnectException(ErrorType.GOOGLE_SIGN_IN_UNAVAILABLE);
		}

		Object authorizedParty = payload.get("azp");
		if (authorizedParty != null && !presenters.contains(authorizedParty)) {
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
				displayName instanceof String name ? name : null,
				email.endsWith("@gmail.com") || StringUtils.hasText(payload.getHostedDomain())
		);
	}

	static boolean isValidSubject(String subject) {
		return subject != null
				&& subject.matches("[A-Za-z0-9._:-]{1,255}");
	}

	private boolean isValidAudienceClaim(Object audience) {
		return audience instanceof String || (audience instanceof List<?> values
				&& !values.isEmpty() && values.stream().allMatch(String.class::isInstance));
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
