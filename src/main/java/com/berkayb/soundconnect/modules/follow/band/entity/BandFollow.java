package com.berkayb.soundconnect.modules.follow.band.entity;

import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.Band;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
		name = "tbl_band_follow",
		uniqueConstraints = {
				@UniqueConstraint(name = "uk_band_follow_follower_band", columnNames = {"follower_id", "band_id"})
		},
		indexes = {
				@Index(name = "idx_band_follow_follower", columnList = "follower_id"),
				@Index(name = "idx_band_follow_band", columnList = "band_id")
		}
)
public class BandFollow extends BaseEntity {
	
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "follower_id", nullable = false)
	private User follower;
	
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "band_id", nullable = false)
	private Band band;
	
	@Column(nullable = false)
	private LocalDateTime followedAt;
}