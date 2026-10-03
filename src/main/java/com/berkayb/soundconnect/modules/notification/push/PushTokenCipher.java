package com.berkayb.soundconnect.modules.notification.push;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Authenticated encryption at rest. Neither this class nor its callers log raw registration tokens. */
@Component
@ConditionalOnProperty(name = "app.notification.push.enabled", havingValue = "true")
public class PushTokenCipher {
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public PushTokenCipher(PushProperties properties) {
        byte[] decoded;
        try { decoded = Base64.getDecoder().decode(properties.getTokenEncryptionKey()); }
        catch (RuntimeException invalid) { throw new IllegalStateException("Push token encryption requires a base64 AES-256 key"); }
        if (decoded.length != 32) throw new IllegalStateException("Push token encryption requires a base64 AES-256 key");
        key = new SecretKeySpec(decoded, "AES");
    }

    public String encrypt(String token) {
        try {
            byte[] nonce = new byte[12]; random.nextBytes(nonce);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD("soundconnect-push-v1".getBytes(StandardCharsets.UTF_8));
            return "v1." + Base64.getEncoder().encodeToString(nonce) + "."
                    + Base64.getEncoder().encodeToString(cipher.doFinal(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) { throw new IllegalStateException("Push token encryption failed"); }
    }

    public String decrypt(String value) {
        try {
            String[] parts = value.split("\\.", -1);
            if (parts.length != 3 || !"v1".equals(parts[0])) throw new IllegalArgumentException();
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Base64.getDecoder().decode(parts[1])));
            cipher.updateAAD("soundconnect-push-v1".getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(Base64.getDecoder().decode(parts[2])), StandardCharsets.UTF_8);
        } catch (Exception failure) { throw new IllegalStateException("Push token decryption failed"); }
    }

    public static String hash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
}
