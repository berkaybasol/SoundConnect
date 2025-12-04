package com.berkayb.soundconnect.modules.track.entity;

import com.berkayb.soundconnect.modules.track.enums.TrackOwnerType;
import com.berkayb.soundconnect.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.util.UUID;

/**
 * Bir muzik kaydini temsil eder.
 * band ve musician profile icin yazildi ama ileride baska profilelar icin de kullanabilirim
 */

@Entity
@Table(name = "tbl_tracks")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class Track extends BaseEntity {
	
	// media asset id. track bir auidio dosyasidir, fiziksel dosya MediaAsset icinde tutulur
	@Column(name = "media_asset_id", nullable = false)
	private UUID mediaAssetId;
	
	// Trackin sahibi kim?
	@Enumerated(EnumType.STRING)
	@Column(name = "owner_type", nullable = false, length = 32)
	private TrackOwnerType ownerType;
	
	 // Track owner id
	@Column(name = "owner_id", nullable = false)
	private UUID ownerId;
	
	@Column(name = "title", nullable = false, length = 256)
	private String title; // Track adi
	
	@Column(name = "duration_seconds")
	private Integer durationSeconds; // metadata (mobil ve web clientler icin faydaliymis)
	
	@Column(name = "bpm")
	private Integer bpm;
}