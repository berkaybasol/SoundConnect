package com.berkayb.soundconnect.shared.util;

import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class EmailUtils {
	private EmailUtils() {}
	
	public static String normalize(String email) {
		return email == null ? "" : email.trim().toLowerCase(Locale.ROOT); // Locale.ROOT = Hicbir dile bagli olma oldugu gibi cevir.
	}

	/** Loglarda tam e-posta adresi yerine geri dondurulemez olmayan, maskeli bir tanim gosterir. */
	public static String maskForLog(String email) {
		String normalized = normalize(email);
		int separator = normalized.indexOf('@');
		if (separator <= 0 || separator == normalized.length() - 1) {
			return "***";
		}
		return normalized.charAt(0) + "***@" + normalized.substring(separator + 1);
	}
}
