package com.berkayb.soundconnect.modules.spotify.support;

import com.berkayb.soundconnect.modules.spotify.dto.response.SpotifyPlaylistMetadataDto;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Central invariant for the server-owned Spotify playlist snapshot.
 *
 * <p>The policy is reused at the HTTP boundary and at aggregate creation so a
 * future internal caller cannot persist client-controlled titles, non-canonical
 * links, or artwork hosted outside Spotify's CDN domains.</p>
 */
public final class SpotifyPlaylistMetadataPolicy {

	private SpotifyPlaylistMetadataPolicy() {
	}

	public static SpotifyPlaylistMetadataDto validateAndNormalize(
			SpotifyPlaylistMetadataDto metadata
	) {
		if (metadata == null) {
			throw invalidMetadata();
		}

		String playlistId = metadata.spotifyPlaylistId();
		final String canonicalUrl;
		try {
			canonicalUrl = SpotifyPlaylistUrlParser.canonicalUrl(playlistId);
		} catch (SoundConnectException exception) {
			throw invalidMetadata();
		}

		String suppliedSpotifyUrl = metadata.spotifyUrl();
		if (suppliedSpotifyUrl == null || suppliedSpotifyUrl.length() > 512
				|| !canonicalUrl.equals(suppliedSpotifyUrl.strip())) {
			throw invalidMetadata();
		}

		String title = metadata.title();
		if (title == null || title.isBlank()) {
			throw invalidMetadata();
		}
		title = title.strip();
		if (title.length() > 255) {
			throw invalidMetadata();
		}

		String coverImageUrl = normalizeCoverImageUrl(metadata.coverImageUrl());
		return new SpotifyPlaylistMetadataDto(
				playlistId,
				title,
				coverImageUrl,
				canonicalUrl
		);
	}

	private static String normalizeCoverImageUrl(String value) {
		if (value == null || value.isBlank() || value.length() > 2048) {
			throw invalidMetadata();
		}
		try {
			URI uri = new URI(value.strip());
			String host = uri.getHost();
			String normalizedHost = host == null ? null : host.toLowerCase(Locale.ROOT);
			boolean trustedHost = normalizedHost != null && (
					normalizedHost.equals("scdn.co")
							|| normalizedHost.endsWith(".scdn.co")
							|| normalizedHost.equals("spotifycdn.com")
							|| normalizedHost.endsWith(".spotifycdn.com")
			);
			String rawPath = uri.getRawPath();
			if (!"https".equalsIgnoreCase(uri.getScheme()) || !trustedHost
					|| uri.getUserInfo() != null
					|| (uri.getPort() != -1 && uri.getPort() != 443)
					|| rawPath == null || rawPath.isBlank()
					|| uri.getFragment() != null) {
				throw invalidMetadata();
			}
			String normalized = uri.toASCIIString();
			if (normalized.length() > 2048) {
				throw invalidMetadata();
			}
			return normalized;
		} catch (URISyntaxException exception) {
			throw invalidMetadata();
		}
	}

	private static SoundConnectException invalidMetadata() {
		return new SoundConnectException(ErrorType.SPOTIFY_PLAYLIST_METADATA_INVALID);
	}
}
