package com.berkayb.soundconnect.auth.model;

/**
 * Google tarafindan imzalanmis ve audience kontrolunden gecmis kimlik bilgisi.
 * Ham ID token uygulamanin geri kalanina tasinmaz veya loglanmaz.
 */
public record VerifiedGoogleIdentity(
		String subject,
		String email,
		String displayName
) {
}
