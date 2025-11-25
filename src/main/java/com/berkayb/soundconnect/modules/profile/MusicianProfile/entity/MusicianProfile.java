package com.berkayb.soundconnect.modules.profile.MusicianProfile.entity;


import com.berkayb.soundconnect.modules.instrument.entity.Instrument;
import com.berkayb.soundconnect.modules.profile.shared.BaseProfile;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.HashSet;
import java.util.Set;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "tbl_musician_profile")
public class MusicianProfile extends BaseProfile {
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
	
	// overthinking modulu icin gerekli belki baska seylerde de kullaniriz.
	@Column(name = "spotify_artist_id", nullable = true, unique = true)
	private String spotifyArtistId;
	
	// TODO Media entity ile ManyToMany seklinde Sesler / Videolar alani yapilcak
	
}