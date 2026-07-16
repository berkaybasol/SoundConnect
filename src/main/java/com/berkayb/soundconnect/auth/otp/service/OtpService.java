package com.berkayb.soundconnect.auth.otp.service;

import com.berkayb.soundconnect.shared.util.EmailUtils;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Creates and verifies short-lived email OTPs in Redis.
 *
 * <p>Every read-modify-write security decision is implemented as a Redis Lua
 * script. Redis executes each script atomically, so concurrent requests cannot
 * reuse a valid code, lose an attempt increment, or issue multiple resend codes
 * during the same cooldown window.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OtpService {

	private static final long VERIFY_SUCCESS = 1L;

	private static final DefaultRedisScript<Long> VERIFY_OTP_ATOMIC = new DefaultRedisScript<>(
			"local value = redis.call('GET', KEYS[1]); "
					+ "if not value then return 0; end; "
					+ "local separator = string.find(value, ':', 1, true); "
					+ "if not separator then redis.call('DEL', KEYS[1]); return -(tonumber(ARGV[2]) + 1); end; "
					+ "local cachedCode = string.sub(value, 1, separator - 1); "
					+ "local attempts = tonumber(string.sub(value, separator + 1)); "
					+ "local maxAttempts = tonumber(ARGV[2]); "
					+ "if not attempts then redis.call('DEL', KEYS[1]); return -(maxAttempts + 1); end; "
					+ "if attempts >= maxAttempts then redis.call('DEL', KEYS[1]); return -attempts; end; "
					+ "if cachedCode == ARGV[1] then redis.call('DEL', KEYS[1]); return 1; end; "
					+ "attempts = attempts + 1; "
					+ "if attempts >= maxAttempts then "
					+ "redis.call('DEL', KEYS[1]); "
					+ "else "
					+ "local ttl = redis.call('PTTL', KEYS[1]); "
					+ "if ttl > 0 then redis.call('PSETEX', KEYS[1], ttl, cachedCode .. ':' .. attempts); "
					+ "else redis.call('DEL', KEYS[1]); end; "
					+ "end; "
					+ "return -attempts;",
			Long.class
	);

	private static final DefaultRedisScript<Long> ACQUIRE_OTP_ISSUE_ATOMIC = new DefaultRedisScript<>(
			"if redis.call('EXISTS', KEYS[2]) == 1 then "
					+ "local ttl = redis.call('PTTL', KEYS[2]); "
					+ "if ttl < 1 then ttl = 1; end; "
					+ "return -ttl; "
					+ "end; "
					+ "redis.call('PSETEX', KEYS[2], ARGV[2], '1'); "
					+ "redis.call('PSETEX', KEYS[1], ARGV[3], ARGV[1] .. ':0'); "
					+ "return 1;",
			Long.class
	);

	private final RedisTemplate<String, String> redisTemplate;

	@Value("${otp.ttl.minutes:${mailersend.otp-validity-minutes:3}}")
	private long otpExpiredMinutes;

	@Value("${otp.length:6}")
	private int otpLength;

	@Value("${otp.max-attempt:5}")
	private int maxAttempt;

	@Value("${otp.resend.cooldown.seconds:30}")
	private long resendCoolDownSeconds;

	private final SecureRandom secureRandom = new SecureRandom();

	@PostConstruct
	void validateAtStartup() {
		validateConfiguration();
	}

	/**
	 * Acquires the same atomic OTP/cooldown claim used by resend. Starting the
	 * guard with the registration code prevents an immediate resend from replacing
	 * the code while the first email is still in flight.
	 */
	public OtpIssueClaim acquireInitialOtp(String email) {
		return acquireOtpIssue(email, "initial", false);
	}

	/**
	 * Atomically consumes a matching OTP or increments its failed-attempt count
	 * while preserving the remaining TTL. A successful code can therefore be
	 * accepted by at most one concurrent request.
	 */
	public boolean verifyOtp(String email, String code) {
		validateConfiguration();
		String otpKey = buildOtpKey(normalize(email));
		Long result = redisTemplate.execute(
				VERIFY_OTP_ATOMIC,
				List.of(otpKey),
				Objects.requireNonNull(code, "code must not be null"),
				Integer.toString(maxAttempt)
		);
		if (result == null) {
			throw new IllegalStateException("Redis returned no OTP verification result");
		}

		if (result == VERIFY_SUCCESS) {
			log.info("OTP verified successfully for {}", EmailUtils.maskForLog(email));
			return true;
		}
		if (result == 0L) {
			log.warn("OTP verify failed: no active code for {}", EmailUtils.maskForLog(email));
			return false;
		}

		long attempts = Math.abs(result);
		if (attempts >= maxAttempt) {
			log.warn("OTP blocked for {} after reaching the attempt limit", EmailUtils.maskForLog(email));
		} else {
			log.warn("Wrong OTP for {} (attempt {}/{})", EmailUtils.maskForLog(email), attempts, maxAttempt);
		}
		return false;
	}

	/**
	 * Atomically acquires the resend cooldown and installs the corresponding OTP.
	 * Exactly one concurrent caller receives the code and is allowed to queue mail.
	 */
	public OtpIssueClaim acquireResendOtp(String email) {
		return acquireOtpIssue(email, "resend", false);
	}

	/**
	 * Uses an isolated namespace to make resend responses indistinguishable for
	 * ineligible addresses without installing a real verification code. A later
	 * registration for that address is therefore not suppressed by the decoy
	 * cooldown.
	 */
	public OtpIssueClaim acquireDecoyResendOtp(String email) {
		return acquireOtpIssue(email, "resend-decoy", true);
	}

	private OtpIssueClaim acquireOtpIssue(String email, String issueKind, boolean decoy) {
		validateConfiguration();
		String normalizedEmail = normalize(email);
		String otpCode = generateRandomOtpCode();
		long cooldownMillis = Duration.ofSeconds(resendCoolDownSeconds).toMillis();
		long otpTtlMillis = Duration.ofMinutes(otpExpiredMinutes).toMillis();

		Long result = redisTemplate.execute(
				ACQUIRE_OTP_ISSUE_ATOMIC,
				List.of(
						decoy ? buildDecoyOtpKey(normalizedEmail) : buildOtpKey(normalizedEmail),
						decoy ? buildDecoyResendGuardKey(normalizedEmail) : buildResendGuardKey(normalizedEmail)
				),
				otpCode,
				Long.toString(cooldownMillis),
				Long.toString(otpTtlMillis)
		);
		if (result == null) {
			throw new IllegalStateException("Redis returned no OTP resend claim result");
		}
		if (result == VERIFY_SUCCESS) {
			log.info("OTP {} issue claimed for email={}", issueKind, EmailUtils.maskForLog(email));
			return OtpIssueClaim.acquired(otpCode);
		}

		long cooldownMillisLeft = result < 0 ? Math.abs(result) : 1L;
		return OtpIssueClaim.rejected(toCeilingSeconds(cooldownMillisLeft));
	}

	public int getOtpRetryCount(String email) {
		String value = redisTemplate.opsForValue().get(buildOtpKey(normalize(email)));
		if (value == null) {
			return 0;
		}
		int separator = value.indexOf(':');
		if (separator < 0 || separator == value.length() - 1) {
			return 0;
		}
		try {
			return Integer.parseInt(value.substring(separator + 1));
		} catch (NumberFormatException ignored) {
			return 0;
		}
	}

	public long getOtpTimeLeftSeconds(String email) {
		Long expire = redisTemplate.getExpire(buildOtpKey(normalize(email)), TimeUnit.SECONDS);
		return expire == null ? 0L : Math.max(expire, 0L);
	}

	public long getResendCooldownLeftSeconds(String email) {
		Long expire = redisTemplate.getExpire(buildResendGuardKey(normalize(email)), TimeUnit.SECONDS);
		return expire == null ? 0L : Math.max(expire, 0L);
	}

	public long getDecoyOtpTimeLeftSeconds(String email) {
		Long expire = redisTemplate.getExpire(buildDecoyOtpKey(normalize(email)), TimeUnit.SECONDS);
		return expire == null ? 0L : Math.max(expire, 0L);
	}

	public long getDecoyResendCooldownLeftSeconds(String email) {
		Long expire = redisTemplate.getExpire(buildDecoyResendGuardKey(normalize(email)), TimeUnit.SECONDS);
		return expire == null ? 0L : Math.max(expire, 0L);
	}

	private String generateRandomOtpCode() {
		int bound = 1;
		for (int index = 0; index < otpLength; index++) {
			bound = Math.multiplyExact(bound, 10);
		}
		int number = secureRandom.nextInt(bound);
		return String.format("%0" + otpLength + "d", number);
	}

	private String normalize(String email) {
		return EmailUtils.normalize(Objects.requireNonNull(email, "email must not be null"));
	}

	private String buildOtpKey(String email) {
		return "soundconnect:otp:{" + hashIdentity(email) + "}:code";
	}

	private String buildResendGuardKey(String email) {
		// The matching hash tag keeps both Lua keys in one Redis Cluster slot.
		return "soundconnect:otp:{" + hashIdentity(email) + "}:resend-guard";
	}

	private String buildDecoyOtpKey(String email) {
		return "soundconnect:otp:{" + hashIdentity(email) + "}:decoy-code";
	}

	private String buildDecoyResendGuardKey(String email) {
		return "soundconnect:otp:{" + hashIdentity(email) + "}:decoy-resend-guard";
	}

	private String hashIdentity(String normalizedEmail) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(normalizedEmail.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is not available", exception);
		}
	}

	private void validateConfiguration() {
		if (otpExpiredMinutes <= 0) {
			throw new IllegalStateException("otp.ttl.minutes must be greater than zero");
		}
		if (otpLength != 6) {
			throw new IllegalStateException("otp.length must be 6 to match the verification API contract");
		}
		if (maxAttempt <= 0) {
			throw new IllegalStateException("otp.max-attempt must be greater than zero");
		}
		if (resendCoolDownSeconds <= 0) {
			throw new IllegalStateException("otp.resend.cooldown.seconds must be greater than zero");
		}
	}

	private static long toCeilingSeconds(long milliseconds) {
		return Math.max(1L, Math.addExact(milliseconds, 999L) / 1_000L);
	}

	/**
	 * The code is deliberately redacted from {@link #toString()}.
	 */
	public record OtpIssueClaim(boolean acquired, String code, long cooldownSeconds) {
		public OtpIssueClaim {
			if (acquired) {
				if (code == null || code.isBlank() || cooldownSeconds != 0L) {
					throw new IllegalArgumentException("An acquired resend claim requires a code and no cooldown");
				}
			} else if (code != null || cooldownSeconds < 1L) {
				throw new IllegalArgumentException("A rejected resend claim requires a positive cooldown and no code");
			}
		}

		private static OtpIssueClaim acquired(String code) {
			return new OtpIssueClaim(true, code, 0L);
		}

		private static OtpIssueClaim rejected(long cooldownSeconds) {
			return new OtpIssueClaim(false, null, cooldownSeconds);
		}

		@Override
		public String toString() {
			return "OtpIssueClaim[acquired=" + acquired + ", cooldownSeconds=" + cooldownSeconds + "]";
		}
	}
}
