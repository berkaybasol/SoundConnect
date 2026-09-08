package com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity;

import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandMemberShipStatus;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandRole;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import java.util.UUID;

/**
 * kullanicinin bir band'e hangi rolde ve hangi statude dahil oldugunu gosterir.
 * bu tablo uyelik tablosudur
 */

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
		name = "tbl_band_member",
		indexes = @Index(name = "idx_band_member_user_status", columnList = "user_id,status"),
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

	/** Immutable identity of this invitation; replaced only when inviting again. */
	@Column(name = "invitation_id", columnDefinition = "uuid")
	private UUID invitationId;

	/** Display-only title. Authorization continues to use bandRole. */
	@Column(name = "member_title", length = 256)
	private String memberTitle;

	@Column(name = "title_version", nullable = false)
	private long titleVersion;

	// Invitation, role and title transitions cannot change this row's identity
	// while it is held in Band.members (a persistent Set with orphan removal).
	@Override
	public final boolean equals(Object other) {
		return this == other || other instanceof BandMember member &&
				getId() != null && getId().equals(member.getId());
	}

	@Override
	public final int hashCode() {
		return BandMember.class.hashCode();
	}

}
