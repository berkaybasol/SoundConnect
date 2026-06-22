package com.berkayb.soundconnect.modules.event.entity;

import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.Band;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.shared.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

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

	private String posterImage; // MediaAsset UUID

	/**
	 * Etkinliğin gerçekleştiği mekan.
	 * Venue zaten city/district/neighborhood ilişkilerini içeriyor.
	 */
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "venue_id", nullable = false)
	private Venue venue;

	/**
	 * Performer:
	 * - Eğer musicianProfile != null -> tek müzisyen çalıyor
	 * - Eğer band != null -> band çalıyor
	 * Bu iki alan aynı anda NULL OLAMAZ, aynı anda DOLU OLAMAZ.
	 * Bunu service katmanında doğrulayacağız.
	 */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "musician_profile_id")
	private MusicianProfile musicianProfile;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "band_id")
	private Band band;

	@Column(length = 50)
	private String manualPerformerName;
}
