package com.berkayb.soundconnect.shared.util;

import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class EmailUtils {
	private EmailUtils() {}
	
	public static String normalize(String email) {
		return email == null ? "" : email.trim().toLowerCase(Locale.ROOT); // Locale.ROOT = Hicbir dile bagli olma oldugu gibi cevir.
	}
}