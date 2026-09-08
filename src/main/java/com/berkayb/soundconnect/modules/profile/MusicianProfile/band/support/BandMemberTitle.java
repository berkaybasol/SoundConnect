package com.berkayb.soundconnect.modules.profile.MusicianProfile.band.support;

import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;

import java.text.Normalizer;
import java.util.regex.Pattern;

/** Bounded, single-line display text, never a permission or membership role. */
public final class BandMemberTitle {
    public static final int MAX_GRAPHEMES = 20;
    public static final int MAX_RAW_CODE_POINTS = 256;
    private static final Pattern GRAPHEME = Pattern.compile("\\X");

    private BandMemberTitle() {}

    public static String normalize(String raw) {
        if (raw == null) return null;
        if (raw.length() > MAX_RAW_CODE_POINTS * 2 ||
                raw.codePointCount(0, raw.length()) > MAX_RAW_CODE_POINTS) {
            throw invalid();
        }
        for (int cp : raw.codePoints().toArray()) {
            int type = Character.getType(cp);
            if (Character.isISOControl(cp) || type == Character.SURROGATE ||
                    type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR ||
                    (type == Character.FORMAT && !isJoiner(cp))) {
                throw invalid();
            }
        }
        String normalized = Normalizer.normalize(trimSpaces(raw), Normalizer.Form.NFC);
        if (normalized.isEmpty()) return null;
        // Canonical decomposition exclusions can expand during NFC. Keep the
        // stored value inside the same PostgreSQL varchar code-point budget.
        if (normalized.codePointCount(0, normalized.length()) > MAX_RAW_CODE_POINTS) throw invalid();
        if (normalized.codePoints().noneMatch(BandMemberTitle::isVisible)) throw invalid();
        var matcher = GRAPHEME.matcher(normalized);
        int count = 0;
        while (matcher.find()) {
            if (++count > MAX_GRAPHEMES) {
                throw invalid();
            }
        }
        return normalized;
    }

    private static boolean isVisible(int cp) {
        int type = Character.getType(cp);
        return Character.isLetterOrDigit(cp) || type == Character.LETTER_NUMBER ||
                type == Character.OTHER_NUMBER ||
                (type >= Character.DASH_PUNCTUATION && type <= Character.OTHER_SYMBOL) ||
                type == Character.INITIAL_QUOTE_PUNCTUATION || type == Character.FINAL_QUOTE_PUNCTUATION;
    }

    private static String trimSpaces(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isSpace(value.codePointAt(start))) {
            start += Character.charCount(value.codePointAt(start));
        }
        while (end > start && isSpace(value.codePointBefore(end))) {
            end -= Character.charCount(value.codePointBefore(end));
        }
        return value.substring(start, end);
    }

    private static boolean isSpace(int cp) {
        return Character.isWhitespace(cp) || Character.isSpaceChar(cp);
    }

    private static boolean isJoiner(int cp) { return cp == 0x200C || cp == 0x200D; }

    private static SoundConnectException invalid() {
        return new SoundConnectException(ErrorType.BAND_MEMBER_TITLE_INVALID);
    }
}
