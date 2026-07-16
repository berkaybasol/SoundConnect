package com.berkayb.soundconnect.modules.media.storage;

import org.springframework.util.StringUtils;

import java.util.Locale;

public final class MediaMimeType {
	private MediaMimeType() {}

	/** Removes optional parameters and normalizes case without changing the wire-level subtype. */
	public static String sanitize(String value) {
		if (!StringUtils.hasText(value)) {
			return "";
		}
		return value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
	}

	/** Treats common platform aliases as the same media format. */
	public static boolean equivalent(String first, String second) {
		return canonical(first).equals(canonical(second));
	}

	private static String canonical(String value) {
		return switch (sanitize(value)) {
			case "audio/x-m4a" -> "audio/mp4";
			case "audio/x-flac" -> "audio/flac";
			default -> sanitize(value);
		};
	}
}
