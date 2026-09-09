package com.berkayb.soundconnect.modules.profile.MusicianProfile.entity;


import com.berkayb.soundconnect.modules.instrument.entity.Instrument;
import com.berkayb.soundconnect.modules.profile.shared.BaseProfile;
import com.berkayb.soundconnect.modules.spotify.dto.response.SpotifyTrackItemDto;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Type;
import org.hibernate.type.SqlTypes;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "tbl_musician_profile")
public class MusicianProfile extends BaseProfile {
	/** Entity identity must not depend on mutable display fields or connection sets. */
	@Override
	public final boolean equals(Object other) {
		if (this == other) return true;
		if (!(other instanceof MusicianProfile profile)) return false;
		return getId() != null && getId().equals(profile.getId());
	}

	/** Stable before/after persistence and identical for an unloaded Hibernate proxy. */
	@Override
	public final int hashCode() {
		return MusicianProfile.class.hashCode();
	}

	@ManyToMany(fetch = FetchType.LAZY)
	@JoinTable(
			name = "musician_profile_instruments",
			joinColumns = @JoinColumn(name = "musician_profile_id"),
			inverseJoinColumns = @JoinColumn (name = "instrument_id")
	)
	@Builder.Default
	private Set<Instrument> instruments = new HashSet<>();
	
	@Column(nullable = true) // sart degil.
	private String stageName;
	
	private String soundcloudUrl;
	
	// Aktif calinan mekanlar
	@Builder.Default
	@ManyToMany(fetch = FetchType.LAZY)
	@JoinTable(
			name = "musician_profile_venues",
			joinColumns = @JoinColumn(name = "musician_profile_id"),
			inverseJoinColumns = @JoinColumn(name = "venue_id")
	)
	private Set<Venue> activeVenues = new HashSet<>();
	
	private String spotifyEmbedUrl; // gomulu spotify oynatici sistemi icin.
	
	@ElementCollection
	@CollectionTable(name = "musician_profile_spotify_tracks", joinColumns = @JoinColumn(name = "profile_id"))
	@Column(name = "track_id")
	@Builder.Default
	private List<String> spotifyTrackIds = new ArrayList<>();
	
	// overthinking modulu icin gerekli belki baska seylerde de kullaniriz.
	@Column(name = "spotify_artist_id", nullable = true, unique = true)
	private String spotifyArtistId;
	
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb")
	@Builder.Default
	private List<SpotifyTrackItemDto> spotifyTracks = new ArrayList<>();
	
}
