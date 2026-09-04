package com.berkayb.soundconnect.modules.spotify.support;

import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strict parser for user-supplied Spotify playlist links.
 *
 * <p>The canonical URL is always built by SoundConnect. Query parameters and
 * fragments from shared links are discarded, and no caller-controlled host is
 * ever forwarded to the Spotify client. This keeps the oEmbed integration out
 * of the SSRF boundary.</p>
 */
public final class SpotifyPlaylistUrlParser {

	private static final String SPOTIFY_HOST = "open.spotify.com";
	private static final Pattern PLAYLIST_PATH = Pattern.compile(
			"^/(?:intl-[a-z]{2}/)?playlist/([A-Za-z0-9]{22})/?$",
			Pattern.CASE_INSENSITIVE
	);

	private SpotifyPlaylistUrlParser() {
	}

	public static SpotifyPlaylistReference parse(String value) {
		if (value == null || value.isBlank() || value.length() > 2048) {
			throw invalidUrl();
		}

		final URI uri;
		try {
			uri = new URI(value.strip());
		} catch (URISyntaxException exception) {
			throw invalidUrl();
		}

		String scheme = uri.getScheme();
		String host = uri.getHost();
		String rawPath = uri.getRawPath();
		if (scheme == null || !"https".equals(scheme.toLowerCase(Locale.ROOT))
				|| host == null || !SPOTIFY_HOST.equals(host.toLowerCase(Locale.ROOT))
				|| uri.getUserInfo() != null
				|| (uri.getPort() != -1 && uri.getPort() != 443)
				|| rawPath == null || rawPath.indexOf('%') >= 0) {
			throw invalidUrl();
		}

		Matcher matcher = PLAYLIST_PATH.matcher(rawPath);
		if (!matcher.matches()) {
			throw invalidUrl();
		}

		String playlistId = matcher.group(1);
		return new SpotifyPlaylistReference(playlistId, canonicalUrl(playlistId));
	}

	public static String canonicalUrl(String playlistId) {
		if (playlistId == null || !playlistId.matches("[A-Za-z0-9]{22}")) {
			throw invalidUrl();
		}
		return "https://" + SPOTIFY_HOST + "/playlist/" + playlistId;
	}

	private static SoundConnectException invalidUrl() {
		return new SoundConnectException(ErrorType.SPOTIFY_PLAYLIST_URL_INVALID);
	}

	public record SpotifyPlaylistReference(String playlistId, String canonicalUrl) {
	}
}
