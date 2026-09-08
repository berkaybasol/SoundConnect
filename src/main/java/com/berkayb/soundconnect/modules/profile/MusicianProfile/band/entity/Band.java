package com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity;

import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Band Entity.
 * Uygulamada muzisyenlerin olusturdugu ve euye olabildigi gruplari temsil eder
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "tbl_band", uniqueConstraints = {
		@UniqueConstraint(name = "uk_band_name", columnNames = "name")
}
)
public class Band extends BaseEntity {
	
	@Column(nullable = false, unique = true, length = 100)
	private String name;
	
	@Column(length = 1024)
	private String description;
	
	private UUID profilePictureMediaId;
	
	private String instagramUrl;
	private String youtubeUrl;
	private String soundCloudUrl;
	
	private String spotifyEmbedUrl;
	private String spotifyArtistId;
	
	@ElementCollection
	@CollectionTable(name = "band_spotify_tracks", joinColumns = @JoinColumn(name = "band_id"))
	@Column(name = "track_id")
	private List<String> spotifyTrackIds;
	
	// yonetim tamamen BandMember tarafindan yapilir
	@OneToMany(mappedBy = "band", cascade = CascadeType.ALL, orphanRemoval = true)
	@Builder.Default
	private Set<BandMember> members = new HashSet<>();
	
	@Builder.Default //eklendi
	@ManyToMany(mappedBy = "activeBands", fetch = FetchType.LAZY) //eklendi
	private Set<Venue> activeVenues = new HashSet<>(); //eklendi

	// A band remains the same persistent entity when renamed or connected to
	// another venue. Mutable fields must not change its owning Set's bucket.
	@Override
	public final boolean equals(Object other) {
		return this == other || other instanceof Band band &&
				getId() != null && getId().equals(band.getId());
	}

	@Override
	public final int hashCode() {
		// Stable before/after generated-ID assignment and for Hibernate proxies.
		return Band.class.hashCode();
	}
}
