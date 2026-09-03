package com.berkayb.soundconnect.auth.service;

import com.berkayb.soundconnect.auth.dto.request.GoogleAuthRequestDto;
import com.berkayb.soundconnect.auth.dto.response.LoginResponse;
import com.berkayb.soundconnect.auth.model.VerifiedGoogleIdentity;
import com.berkayb.soundconnect.auth.ratelimit.AuthAccountRateLimitGuard;
import com.berkayb.soundconnect.auth.security.GoogleIdTokenValidator;
import com.berkayb.soundconnect.auth.security.JwtTokenProvider;
import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.support.ListenerProfileChoiceStatusReader;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.AuthProvider;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import com.berkayb.soundconnect.shared.util.EmailUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Google ID token ile login/register akisini yonetir. Token dogrulamasi ayri
 * bir bilesendedir; ham token bu servise ait loglara veya kalici veriye girmez.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GoogleAuthService {

	private final UserRepository userRepository;
	private final JwtTokenProvider jwtTokenProvider;
	private final GoogleIdTokenValidator googleIdTokenValidator;
	private final PasswordEncoder passwordEncoder;
	private final AuthAccountRateLimitGuard accountRateLimitGuard;
	private final ListenerProfileChoiceStatusReader listenerProfileChoiceStatusReader;

	@Transactional
	public BaseResponse<LoginResponse> loginWithGoogle(GoogleAuthRequestDto dto) {
		VerifiedGoogleIdentity identity = googleIdTokenValidator.verify(dto.idToken());
		String email = EmailUtils.normalize(identity.email());
		String subject = identity.subject();
		accountRateLimitGuard.checkGoogleLogin(subject);

		User user = userRepository.findByProviderAndProviderSubject(AuthProvider.GOOGLE, subject)
				.orElse(null);
		if (user == null) {
			User emailAccount = userRepository.findByEmailForUpdate(email).orElse(null);
			if (emailAccount == null) {
				user = createGoogleOnboardingUser(subject, email);
				log.info("New Google account created. userId={}", user.getId());
			} else {
				user = emailAccount;
				validateExistingGoogleAccount(user, subject);
				// One-time, safe migration for accounts that were themselves created by
				// the legacy Google flow. Local accounts are rejected above and never
				// linked solely because a token asserts the same email address.
				if (user.getProviderSubject() == null || user.getProviderSubject().isBlank()) {
					user.setProviderSubject(subject);
				}
				user.setEmail(email);
				clearLegacyOnboardingRole(user);
				userRepository.save(user);
			}
		} else {
			validateExistingGoogleAccount(user, subject);
			if (clearLegacyOnboardingRole(user)) {
				userRepository.save(user);
			}
		}

		UserDetailsImpl userDetails = UserDetailsImpl.fromUser(user);
		String token = jwtTokenProvider.generateToken(userDetails);

		return BaseResponse.<LoginResponse>builder()
				.success(true)
				.message("Google ile giris basarili")
				.code(200)
				.data(LoginResponse.fromUser(
						token,
						user,
						listenerProfileChoiceStatusReader.requiresChoice(user)
				))
				.build();
	}

	private User createGoogleOnboardingUser(String subject, String email) {
		User user = User.builder()
				.username(generateAvailableGoogleUsername(subject))
				.email(email)
				// Google hesaplari local parola ile kullanilamaz. DB not-null
				// kontrati icin tahmin edilemez bir BCrypt degeri saklanir.
				.password(passwordEncoder.encode(UUID.randomUUID().toString()))
				.emailVerified(true)
				.provider(AuthProvider.GOOGLE)
				.providerSubject(subject)
				.status(UserStatus.ACTIVE)
				// Rol yalniz authenticated complete-profile adiminda atanir.
				.roles(new HashSet<>())
				.build();
		return userRepository.save(user);
	}

	private void validateExistingGoogleAccount(User user, String subject) {
		if (user.getProvider() != AuthProvider.GOOGLE) {
			throw new SoundConnectException(
					ErrorType.UNAUTHORIZED,
					List.of("Bu e-posta adresi Google girisi ile kullanilamaz.")
			);
		}
		if (user.getProviderSubject() != null
				&& !user.getProviderSubject().isBlank()
				&& !user.getProviderSubject().equals(subject)) {
			throw new SoundConnectException(
					ErrorType.UNAUTHORIZED,
					List.of("Google kimligi bu hesapla eslesmiyor.")
			);
		}
		if (!Boolean.TRUE.equals(user.getEmailVerified()) || user.getStatus() != UserStatus.ACTIVE) {
			throw new SoundConnectException(
					ErrorType.FORBIDDEN_ACCESS,
					List.of("Google hesabi aktif degil.")
			);
		}
	}

	private boolean clearLegacyOnboardingRole(User user) {
		if (!hasLegacyOnboardingRole(user)) {
			return false;
		}
		// Legacy akista Google hesaplarina kullanici secimi olmadan tek basina
		// ROLE_USER ataniyordu. Bu placeholder temizlenerek kullanici guvenli
		// allowlist uzerinden gercek profil tipini secebilir.
		user.setRoles(new HashSet<>());
		return true;
	}

	private String generateAvailableGoogleUsername(String subject) {
		String digest = sha256(subject);
		String candidate = "g_" + digest.substring(0, 24);
		if (!userRepository.existsByUsername(candidate)) {
			return candidate;
		}

		// A local user can pre-claim a predictable value. A random suffix keeps
		// that collision from becoming a denial-of-service against Google signup.
		for (int attempt = 0; attempt < 5; attempt++) {
			candidate = "g_" + digest.substring(0, 12) + "_"
					+ UUID.randomUUID().toString().replace("-", "").substring(0, 8);
			if (!userRepository.existsByUsername(candidate)) {
				return candidate;
			}
		}
		throw new IllegalStateException("Could not allocate a Google account username");
	}

	private String sha256(String value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(value.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is not available", exception);
		}
	}

	private boolean hasLegacyOnboardingRole(User user) {
		return user.getRoles() != null
				&& user.getRoles().size() == 1
				&& user.getRoles().stream().anyMatch(role -> "ROLE_USER".equals(role.getName()));
	}
}
