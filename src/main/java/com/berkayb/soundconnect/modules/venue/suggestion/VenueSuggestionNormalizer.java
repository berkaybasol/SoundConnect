package com.berkayb.soundconnect.modules.venue.suggestion;

import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Locale;

final class VenueSuggestionNormalizer {
    private VenueSuggestionNormalizer() { }
    static String name(String raw) {
        if (raw == null || raw.length() > 400) throw invalid();
        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC);
        if (normalized.codePoints().anyMatch(c -> Character.isISOControl(c)
                || Character.getType(c) == Character.FORMAT || c == 0x2028 || c == 0x2029
                || Character.getType(c) == Character.SURROGATE)) throw invalid();
        normalized = normalized.replaceAll("[\\p{Z}\\s]+", " ").strip();
        int length = normalized.codePointCount(0, normalized.length());
        if (length < 2 || length > 100) throw invalid();
        return normalized;
    }
    static String folded(String name) { return name.toLowerCase(Locale.forLanguageTag("tr-TR")); }
    static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    static SoundConnectException invalid() { return new SoundConnectException(ErrorType.VENUE_SUGGESTION_INVALID); }
}
