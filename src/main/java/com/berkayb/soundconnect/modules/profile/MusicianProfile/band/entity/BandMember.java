package com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity;

import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandMemberShipStatus;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandRole;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * kullanicinin bir band'e hangi rolde ve hangi statude dahil oldugunu gosterir.
 * bu tablo uyelik tablosudur
 */

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@EqualsAndHashCode(callSuper = true, exclude = {"band", "user"})
@Table(
		name = "tbl_band_member",
		uniqueConstraints = {
				@UniqueConstraint(name = "uk_band_user", columnNames = {"band_id", "user_id"})
		}
)
public class BandMember extends BaseEntity {
	@ManyToOne (fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "band_id", nullable = false)
	private Band band; // habgi bande uye
	
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private User user; // hangi kullanici
	
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private BandRole bandRole;
	
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private BandMemberShipStatus status;

}