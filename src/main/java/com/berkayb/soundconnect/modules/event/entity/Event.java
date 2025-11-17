package com.berkayb.soundconnect.modules.event.entity;

import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.Band;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "tbl_event")
public class Event extends BaseEntity {
	
	@Column(nullable = false)
	private String title;
	
	@Column(length = 500)
	private String description;
	
	@Column(nullable = false)
	private LocalDate eventDate;
	
	@Column(nullable = false)
	private LocalTime startTime;
	
	private LocalTime endTime;
	
	private String posterImage; // S3 / CDN görüntü linki
	
	/**
	 * Etkinliğin gerçekleştiği mekan.
	 * Venue zaten city/district/neighborhood ilişkilerini içeriyor.
	 */
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "venue_id", nullable = false)
	private Venue venue;
	
	/**
	 * Performer:
	 * - Eğer musicianProfile != null → tek müzisyen çalıyor
	 * - Eğer band != null → band çalıyor
	 * Bu iki alan aynı anda NULL OLAMAZ, aynı anda DOLU OLAMAZ.
	 * Bunu service katmanında doğrulayacağız.
	 */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "musician_profile_id")
	private MusicianProfile musicianProfile;
	
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "band_id")
	private Band band;
}