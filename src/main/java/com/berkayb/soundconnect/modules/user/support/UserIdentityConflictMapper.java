package com.berkayb.soundconnect.modules.user.support;

import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.util.Locale;

public final class UserIdentityConflictMapper {
	private static final String UNIQUE_VIOLATION_SQL_STATE = "23505";

	private UserIdentityConflictMapper() {
	}

	public static RuntimeException map(DataIntegrityViolationException exception) {
		if (!isUniqueViolation(exception)) {
			return exception;
		}

		String diagnostic = diagnosticText(exception);
		if (diagnostic.contains("ux_tbl_user_username_canonical")
				|| diagnostic.contains("user_name")) {
			return new SoundConnectException(ErrorType.USER_ALREADY_EXISTS);
		}
		if (diagnostic.contains("(email)")
				|| diagnostic.contains(".email")
				|| diagnostic.contains(" email ")) {
			return new SoundConnectException(ErrorType.EMAIL_ALREADY_EXISTS);
		}
		return exception;
	}

	private static boolean isUniqueViolation(Throwable throwable) {
		for (Throwable current = throwable; current != null; current = current.getCause()) {
			if (current instanceof SQLException sqlException
					&& UNIQUE_VIOLATION_SQL_STATE.equals(sqlException.getSQLState())) {
				return true;
			}
			String message = current.getMessage();
			if (message != null) {
				String normalized = message.toLowerCase(Locale.ROOT);
				if (normalized.contains("duplicate key")
						|| normalized.contains("unique constraint")
						|| normalized.contains("unique index")) {
					return true;
				}
			}
		}
		return false;
	}

	private static String diagnosticText(Throwable throwable) {
		StringBuilder diagnostic = new StringBuilder();
		for (Throwable current = throwable; current != null; current = current.getCause()) {
			if (current.getMessage() != null) {
				diagnostic.append(' ')
						.append(current.getMessage().toLowerCase(Locale.ROOT));
			}
		}
		return diagnostic.toString();
	}
}
