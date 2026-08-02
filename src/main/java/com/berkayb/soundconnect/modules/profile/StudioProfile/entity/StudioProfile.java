package com.berkayb.soundconnect.modules.profile.StudioProfile.entity;

import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.entity.District;
import com.berkayb.soundconnect.modules.location.entity.Neighborhood;
import com.berkayb.soundconnect.modules.profile.shared.BaseProfile;
import com.berkayb.soundconnect.modules.spotify.dto.response.SpotifyTrackItemDto;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.JdbcTypeCode;
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
@Table(name = "tbl_studio_profile")
public class StudioProfile extends BaseProfile {
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "city_id")
	private City city;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "district_id")
	private District district;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "neighborhood_id")
	private Neighborhood neighborhood;

	@Column(name = "time_zone", nullable = false, length = 64)
	@Builder.Default
	private String timeZone = "Europe/Istanbul";

	@Version
	@Column(name = "version", nullable = false)
	@Builder.Default
	private long version = 0L;

	@ElementCollection(fetch = FetchType.LAZY)
	@CollectionTable(
			name = "studio_profile_spotify_tracks",
			joinColumns = @JoinColumn(name = "studio_profile_id")
	)
	@OrderColumn(name = "position")
	@Column(name = "track_id", nullable = false, length = 64)
	@Builder.Default
	private List<String> spotifyTrackIds = new ArrayList<>();

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "spotify_tracks", columnDefinition = "jsonb")
	@Builder.Default
	private List<SpotifyTrackItemDto> spotifyTracks = new ArrayList<>();

	@ElementCollection(fetch = FetchType.LAZY)
	@CollectionTable(
			name = "studio_facilities",
			joinColumns = @JoinColumn(name = "studio_profile_id")
	)
	@Column(name = "facility")
	@Builder.Default
	private Set<String> facilities = new HashSet<>();
}
