package com.berkayb.soundconnect.auth.passwordreset.service;

import com.berkayb.soundconnect.auth.otp.service.OtpService;
import com.berkayb.soundconnect.auth.passwordreset.dto.request.ForgotPasswordRequestDto;
import com.berkayb.soundconnect.auth.passwordreset.dto.request.ResetPasswordRequestDto;
import com.berkayb.soundconnect.auth.passwordreset.dto.response.PasswordResetAccountResponseDto;
import com.berkayb.soundconnect.auth.ratelimit.AuthAccountRateLimitGuard;
import com.berkayb.soundconnect.modules.profile.shared.resolver.dto.UserProfileTargetDto;
import com.berkayb.soundconnect.modules.profile.shared.resolver.service.PublicProfileResolverService;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.AuthProvider;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.RateLimitedException;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import com.berkayb.soundconnect.shared.util.EmailUtils;
import com.berkayb.soundconnect.shared.util.UsernameUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
@RequiredArgsConstructor
@Slf4j
public class PasswordResetService {

	private static final String RESET_CODE_SENT_MESSAGE =
			"Şifre sıfırlama kodu e-posta adresinize gönderildi.";

	private final UserRepository userRepository;
	private final OtpService otpService;
	private final PasswordResetMailService mailService;
	private final PasswordEncoder passwordEncoder;
	private final AuthAccountRateLimitGuard accountRateLimitGuard;
	private final PublicProfileResolverService publicProfileResolverService;

	public BaseResponse<PasswordResetAccountResponseDto> resolveAccount(
			ForgotPasswordRequestDto request
	) {
		ResolvedIdentifier resolved = resolveIdentifier(request.identifier());
		accountRateLimitGuard.checkPasswordResetLookup(accountRateLimitKey(resolved));
		User account = requireAccount(resolved);
		assertLocalProvider(account);

		PasswordResetAccountResponseDto data = new PasswordResetAccountResponseDto(
				UsernameUtils.normalize(account.getUsername()),
				resolveProfilePictureUrl(account)
		);
		return BaseResponse.<PasswordResetAccountResponseDto>builder()
				.success(true)
				.message("Hesap bulundu.")
				.code(HttpStatus.OK.value())
				.data(data)
				.build();
	}

	public BaseResponse<Void> requestPasswordReset(ForgotPasswordRequestDto request) {
		ResolvedIdentifier resolved = resolveIdentifier(request.identifier());
		accountRateLimitGuard.checkPasswordResetRequest(accountRateLimitKey(resolved));
		User account = requireAccount(resolved);
		assertLocalProvider(account);

		String email = EmailUtils.normalize(account.getEmail());
		OtpService.OtpIssueClaim claim = otpService.acquirePasswordResetOtp(email);
		if (!claim.acquired()) {
			throw new RateLimitedException(
					ErrorType.AUTH_RATE_LIMITED,
					claim.cooldownSeconds());
		}

		try {
			mailService.queueResetCode(email, claim.code());
		} catch (RuntimeException exception) {
			cancelFailedMailClaim(email, claim.code());
			log.error(
					"Password reset mail could not be queued for email={}, exceptionType={}",
					EmailUtils.maskForLog(email),
					exception.getClass().getSimpleName());
			throw new SoundConnectException(ErrorType.PASSWORD_RESET_DELIVERY_FAILED);
		}

		return BaseResponse.<Void>builder()
				.success(true)
				.message(RESET_CODE_SENT_MESSAGE)
				.code(HttpStatus.OK.value())
				.build();
	}

	private void cancelFailedMailClaim(String email, String code) {
		try {
			if (!otpService.cancelPasswordResetOtpIssue(email, code)) {
				log.warn(
						"Password reset OTP cancellation did not match an active claim for email={}",
						EmailUtils.maskForLog(email));
			}
		} catch (RuntimeException exception) {
			log.error(
					"Password reset OTP cancellation failed for email={}, exceptionType={}",
					EmailUtils.maskForLog(email),
					exception.getClass().getSimpleName());
		}
	}

	/**
	 * Redis consumes a valid code atomically before the database row is locked. At
	 * most one concurrent request can therefore reach the password update. Any
	 * identity change between lookup and lock fails closed after consumption.
	 */
	@Transactional
	public BaseResponse<Void> resetPassword(ResetPasswordRequestDto request) {
		ResolvedIdentifier resolved = resolveIdentifier(request.identifier());
		accountRateLimitGuard.checkPasswordResetConfirm(accountRateLimitKey(resolved));
		User account = requireAccount(resolved);
		assertLocalProvider(account);

		String email = EmailUtils.normalize(account.getEmail());
		if (!otpService.verifyPasswordResetOtp(email, request.code())) {
			throw invalidResetCode();
		}

		User user = userRepository.findByIdForUpdate(account.getId())
				.orElseThrow(() -> notFound(resolved.type()));
		assertLockedIdentityStillMatches(resolved, user, email);

		user.setPassword(passwordEncoder.encode(request.password()));
		userRepository.save(user);

		return BaseResponse.<Void>builder()
				.success(true)
				.message("Şifreniz başarıyla güncellendi.")
				.code(HttpStatus.OK.value())
				.build();
	}

	private ResolvedIdentifier resolveIdentifier(String rawIdentifier) {
		String stripped = UsernameUtils.stripBoundaryWhitespace(rawIdentifier);
		if (stripped != null && stripped.indexOf('@') >= 0) {
			String email = EmailUtils.normalize(stripped);
			User emailAccount = userRepository.findByEmail(email).orElse(null);
			if (emailAccount != null) {
				return new ResolvedIdentifier(IdentifierType.EMAIL, email, emailAccount);
			}

			// Usernames historically have no character allowlist, so an existing
			// username containing '@' remains recoverable. Email wins only when both
			// identities happen to use the same text.
			String username = UsernameUtils.normalize(stripped);
			if (UsernameUtils.hasValidCanonicalLength(username)) {
				User usernameAccount = userRepository.findByUsername(username).orElse(null);
				if (usernameAccount != null) {
					return new ResolvedIdentifier(IdentifierType.USERNAME, username, usernameAccount);
				}
			}
			return new ResolvedIdentifier(IdentifierType.EMAIL, email, null);
		}

		String username = UsernameUtils.normalizeAndValidate(stripped);
		User user = userRepository.findByUsername(username).orElse(null);
		return new ResolvedIdentifier(IdentifierType.USERNAME, username, user);
	}

	private User requireAccount(ResolvedIdentifier resolved) {
		if (resolved.user() == null) {
			throw notFound(resolved.type());
		}
		return resolved.user();
	}

	private void assertLocalProvider(User user) {
		if (user.getProvider() != AuthProvider.LOCAL) {
			throw new SoundConnectException(ErrorType.PASSWORD_RESET_PROVIDER_UNSUPPORTED);
		}
	}

	private void assertLockedIdentityStillMatches(
			ResolvedIdentifier resolved,
			User lockedUser,
			String otpEmail
	) {
		assertLocalProvider(lockedUser);
		String currentIdentifier = switch (resolved.type()) {
			case EMAIL -> EmailUtils.normalize(lockedUser.getEmail());
			case USERNAME -> UsernameUtils.normalize(lockedUser.getUsername());
		};
		if (!resolved.canonicalValue().equals(currentIdentifier)) {
			throw notFound(resolved.type());
		}
		if (!otpEmail.equals(EmailUtils.normalize(lockedUser.getEmail()))) {
			throw invalidResetCode();
		}
	}

	private String accountRateLimitKey(ResolvedIdentifier resolved) {
		if (resolved.user() != null) {
			return "user-id:" + resolved.user().getId();
		}
		return "identifier:"
				+ resolved.type().name().toLowerCase(Locale.ROOT)
				+ ":" + resolved.canonicalValue();
	}

	private String resolveProfilePictureUrl(User account) {
		try {
			return publicProfileResolverService.resolveByUserId(account.getId()).profiles()
					.stream()
					.map(UserProfileTargetDto::profilePictureUrl)
					.filter(url -> url != null && !url.isBlank())
					.findFirst()
					.orElse(null);
		} catch (RuntimeException exception) {
			log.warn(
					"Password reset profile picture could not be resolved for userId={}, exceptionType={}",
					account.getId(),
					exception.getClass().getSimpleName());
			return null;
		}
	}

	private static SoundConnectException notFound(IdentifierType type) {
		return new SoundConnectException(type == IdentifierType.EMAIL
				? ErrorType.PASSWORD_RESET_EMAIL_NOT_FOUND
				: ErrorType.PASSWORD_RESET_USERNAME_NOT_FOUND);
	}

	private static SoundConnectException invalidResetCode() {
		return new SoundConnectException(ErrorType.PASSWORD_RESET_CODE_INVALID);
	}

	private enum IdentifierType {
		EMAIL,
		USERNAME
	}

	private record ResolvedIdentifier(
			IdentifierType type,
			String canonicalValue,
			User user
	) {
	}
}
