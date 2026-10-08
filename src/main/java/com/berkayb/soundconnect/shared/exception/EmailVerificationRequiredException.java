package com.berkayb.soundconnect.shared.exception;

import com.berkayb.soundconnect.shared.util.EmailUtils;
import lombok.Getter;

/**
 * A recovery destination disclosed only after the login password is verified.
 * The dedicated handler exposes this canonical address as details[0]; arbitrary
 * SoundConnectException details remain private.
 */
@Getter
public final class EmailVerificationRequiredException extends SoundConnectException {

	private final String email;

	public EmailVerificationRequiredException(String email) {
		super(ErrorType.EMAIL_VERIFICATION_REQUIRED);
		this.email = EmailUtils.normalize(email);
	}
}
