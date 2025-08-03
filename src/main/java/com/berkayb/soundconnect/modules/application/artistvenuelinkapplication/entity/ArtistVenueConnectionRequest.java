package com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.entity;

import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.enums.RequestByType;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.enums.RequestStatus;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "artist_venue_connection_requests")
public class ArtistVenueConnectionRequest extends BaseEntity {
	
	@ManyToOne (fetch = FetchType.LAZY)
	@JoinColumn(name = "musician_profile_id")
	private MusicianProfile musicianProfile;
	
	@ManyToOne (fetch = FetchType.LAZY)
	@JoinColumn(name = "venue_id")
	private Venue venue;
	
	@Enumerated(EnumType.STRING)
	private RequestStatus status;
	
	@Enumerated(EnumType.STRING)
	private RequestByType requestByType;
	
	private String message;
}