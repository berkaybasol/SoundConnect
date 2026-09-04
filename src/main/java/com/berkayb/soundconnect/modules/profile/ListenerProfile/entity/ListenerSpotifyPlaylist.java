package com.berkayb.soundconnect.modules.profile.ListenerProfile.entity;

import com.berkayb.soundconnect.modules.spotify.dto.response.SpotifyPlaylistMetadataDto;
import com.berkayb.soundconnect.modules.spotify.support.SpotifyPlaylistMetadataPolicy;
import com.berkayb.soundconnect.shared.entity.BaseEntity;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.util.Objects;

@Getter
@NoArgsConstructor
@Entity
@Table(
		name = "tbl_listener_spotify_playlist",
		uniqueConstraints = {
				@UniqueConstraint(
						name = "uk_listener_spotify_playlist_position",
						columnNames = {"listener_profile_id", "position"}
				),
				@UniqueConstraint(
						name = "uk_listener_spotify_playlist_spotify_id",
						columnNames = {"listener_profile_id", "spotify_playlist_id"}
				)
		},
		indexes = @Index(
				name = "idx_listener_spotify_playlist_profile",
				columnList = "listener_profile_id,position"
		)
)
@Check(constraints = "position between 0 and 3")
public class ListenerSpotifyPlaylist extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "listener_profile_id", nullable = false, updatable = false)
	@OnDelete(action = OnDeleteAction.CASCADE)
	private ListenerProfile listenerProfile;

	@Column(name = "spotify_playlist_id", nullable = false, updatable = false, length = 64)
	private String spotifyPlaylistId;

	@Column(nullable = false, length = 255)
	private String title;

	@Column(name = "cover_image_url", nullable = false, length = 2048)
	private String coverImageUrl;

	@Column(name = "spotify_url", nullable = false, length = 512)
	private String spotifyUrl;

	@Column(nullable = false)
	private int position;

	public static ListenerSpotifyPlaylist create(
			ListenerProfile listenerProfile,
			SpotifyPlaylistMetadataDto metadata,
			int position
	) {
		Objects.requireNonNull(listenerProfile, "listenerProfile");
		if (position < 0 || position > 3) {
			throw new IllegalArgumentException("Invalid listener Spotify playlist snapshot");
		}
		final SpotifyPlaylistMetadataDto validated;
		try {
			validated = SpotifyPlaylistMetadataPolicy.validateAndNormalize(metadata);
		} catch (SoundConnectException exception) {
			throw new IllegalArgumentException("Invalid listener Spotify playlist snapshot", exception);
		}

		ListenerSpotifyPlaylist playlist = new ListenerSpotifyPlaylist();
		playlist.listenerProfile = listenerProfile;
		playlist.spotifyPlaylistId = validated.spotifyPlaylistId();
		playlist.title = validated.title();
		playlist.coverImageUrl = validated.coverImageUrl();
		playlist.spotifyUrl = validated.spotifyUrl();
		playlist.position = position;
		return playlist;
	}
}
