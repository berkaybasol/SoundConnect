package com.berkayb.soundconnect.modules.spotify.dto.response;

/**
 * Server-authoritative Spotify playlist snapshot. Values originate from the
 * official oEmbed response; clients never submit title or artwork metadata.
 */
public record SpotifyPlaylistMetadataDto(
		String spotifyPlaylistId,
		String title,
		String coverImageUrl,
		String spotifyUrl
) {
}
