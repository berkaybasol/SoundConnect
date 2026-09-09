package com.berkayb.soundconnect.modules.profile.shared;

import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.Locale;
import java.util.regex.Pattern;

/** Common write-boundary rules for optional public profile content. */
public final class ProfileInputValidation {
    private static final Pattern URI_SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*:");

    private ProfileInputValidation() {}

    public static void text(String value, int maximum, String field) {
        if (value != null && value.length() > maximum) {
            throw invalid(field + " cannot exceed " + maximum + " characters");
        }
    }

    public static void collection(Collection<?> values, int maximum, String field) {
        if (values != null && (values.size() > maximum || values.stream().anyMatch(java.util.Objects::isNull))) {
            throw invalid(field + " contains too many or null items");
        }
    }

    public static void trackIds(Collection<String> values) {
        collection(values, 50, "spotifyTrackIds");
        if (values != null && values.stream().anyMatch(value -> value.isBlank() || value.length() > 64)) {
            throw invalid("spotifyTrackIds contains an invalid id");
        }
    }

    /** Null is an omitted update; an empty string deliberately clears the link. */
    public static String webUrl(String value, String field) {
        if (value == null) return null;
        String trimmed = value.strip();
        if (trimmed.isEmpty()) return "";
        text(trimmed, 255, field);
        String candidate = URI_SCHEME.matcher(trimmed).find() ? trimmed : "https://" + trimmed;
        try {
            URI uri = new URI(candidate);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null
                    || uri.getHost().isBlank() || uri.getUserInfo() != null
                    || uri.getPort() < -1 || uri.getPort() > 65535) {
                throw invalid(field + " must be an http(s) URL");
            }
            // Preserve already-escaped paths/query values; URI's component
            // constructor would escape their percent signs for a second time.
            String normalized = uri.toASCIIString();
            text(normalized, 255, field);
            return normalized;
        } catch (URISyntaxException exception) {
            throw invalid(field + " must be an http(s) URL");
        }
    }

    public static String optionalIdentifier(String value) {
        if (value == null || value.isBlank()) return null;
        String identifier = value.strip();
        text(identifier, 255, "spotifyArtistId");
        return identifier;
    }

    public static SoundConnectException invalid(String message) {
        return new SoundConnectException(ErrorType.VALIDATION_ERROR, message);
    }
}
