package com.berkayb.soundconnect.modules.overthinking.support;

import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Pattern;

/** Mirrors the existing Spotify playlist link contract; only the canonical ID reaches the API. */
public record OverthinkingSpotifyReference(String trackId, String canonicalUrl) {
    private static final Pattern TRACK_PATH = Pattern.compile(
            "^/(?:intl-[a-z]{2}/)?track/([A-Za-z0-9]{22})/?$", Pattern.CASE_INSENSITIVE);

    public static OverthinkingSpotifyReference parse(String value) {
        if (value == null || value.isBlank() || value.length() > 1024) throw invalid();
        try {
            URI uri = new URI(value.strip());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !"open.spotify.com".equalsIgnoreCase(uri.getHost())
                    || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)
                    || uri.getRawPath() == null) throw invalid();
            var match = TRACK_PATH.matcher(uri.getRawPath());
            if (!match.matches()) throw invalid();
            String id = match.group(1);
            return new OverthinkingSpotifyReference(id, "https://open.spotify.com/track/" + id);
        } catch (URISyntaxException exception) {
            throw invalid();
        }
    }

    public static OverthinkingSpotifyReference parseOrNull(String value) {
        try { return parse(value); } catch (SoundConnectException ignored) { return null; }
    }

    public static String artistId(String value) {
        String normalized = text(value, 255);
        if (normalized != null && !normalized.matches("[A-Za-z0-9]{22}")) throw invalid();
        return normalized;
    }

    public static String text(String value, int maximum) {
        if (value != null && value.length() > maximum) throw invalid();
        return value == null || value.isBlank() ? null : value.strip();
    }

    public static String imageUrl(String value) {
        String normalized = text(value, 1024);
        if (normalized == null) return null;
        try {
            URI uri = new URI(normalized);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !"i.scdn.co".equalsIgnoreCase(uri.getHost())
                    || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)
                    || uri.getRawPath() == null || !uri.getRawPath().matches("/image/[A-Za-z0-9]+")
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) throw invalid();
            return normalized;
        } catch (URISyntaxException exception) {
            throw invalid();
        }
    }

    /** Existing rows and provider snapshots must pass the same trust boundary as new writes. */
    public static String imageUrlOrNull(String value) {
        try { return imageUrl(value); } catch (SoundConnectException ignored) { return null; }
    }

    private static SoundConnectException invalid() {
        return new SoundConnectException(ErrorType.OVERTHINKING_SPOTIFY_SOURCE_INVALID);
    }
}
