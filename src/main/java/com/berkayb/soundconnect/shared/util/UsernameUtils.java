package com.berkayb.soundconnect.shared.util;

import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;

public final class UsernameUtils {
	public static final int MIN_LENGTH = 3;
	public static final int MAX_LENGTH = 30;
	public static final String LENGTH_MESSAGE = "Kullanıcı adı 3 ile 30 karakter arasında olmalıdır.";

	private UsernameUtils() {
	}

	/**
	 * Canonical username contract shared with Dart and
	 * {@code public.soundconnect_canonical_username(text)} in the PostgreSQL
	 * rollout script. Lowercasing is deliberately applied one code point at a
	 * time: replacing this with {@link String#toLowerCase()} or
	 * locale-aware full mappings changes dotted-I and contextual sigma results
	 * and can make cross-runtime login lookups fail.
	 */
	public static String normalize(String username) {
		if (username == null) {
			return null;
		}
		String stripped = stripBoundaryWhitespace(username);
		StringBuilder canonical = new StringBuilder(stripped.length());
		for (int offset = 0; offset < stripped.length(); ) {
			int codePoint = stripped.codePointAt(offset);
			canonical.appendCodePoint(Character.toLowerCase(codePoint));
			offset += Character.charCount(codePoint);
		}
		return canonical.toString();
	}

	public static boolean hasValidCanonicalLength(String username) {
		String normalized = normalize(username);
		return normalized != null
				&& normalized.length() >= MIN_LENGTH
				&& normalized.length() <= MAX_LENGTH;
	}

	public static String normalizeAndValidate(String username) {
		String normalized = normalize(username);
		if (!hasValidCanonicalLength(normalized)) {
			throw new SoundConnectException(ErrorType.VALIDATION_ERROR, LENGTH_MESSAGE);
		}
		return normalized;
	}

	public static String stripBoundaryWhitespace(String value) {
		if (value == null) {
			return null;
		}
		int start = 0;
		int end = value.length();
		while (start < end) {
			int codePoint = value.codePointAt(start);
			if (!isBoundaryWhitespace(codePoint)) {
				break;
			}
			start += Character.charCount(codePoint);
		}
		while (end > start) {
			int codePoint = value.codePointBefore(end);
			if (!isBoundaryWhitespace(codePoint)) {
				break;
			}
			end -= Character.charCount(codePoint);
		}
		return value.substring(start, end);
	}

	private static boolean isBoundaryWhitespace(int codePoint) {
		return (codePoint >= 0x0009 && codePoint <= 0x000D)
				|| codePoint == 0x0020
				|| codePoint == 0x0085
				|| codePoint == 0x00A0
				|| codePoint == 0x1680
				|| (codePoint >= 0x2000 && codePoint <= 0x200A)
				|| codePoint == 0x2028
				|| codePoint == 0x2029
				|| codePoint == 0x202F
				|| codePoint == 0x205F
				|| codePoint == 0x3000
				|| codePoint == 0xFEFF;
	}
}
