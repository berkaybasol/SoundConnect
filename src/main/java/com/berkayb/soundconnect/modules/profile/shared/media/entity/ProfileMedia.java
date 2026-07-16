package com.berkayb.soundconnect.modules.profile.shared.media.entity;

import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileMediaRole;
import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileType;
import com.berkayb.soundconnect.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.UUID;

/**
 * Bir profile ait medya varliklarinin UI baglamini temsil eder.
 * MediaAsset'e sahip olmaz
 * Sadece mediaAssetId referansi tutar.
 */

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
		name = "tbl_profile_media",
		uniqueConstraints = @UniqueConstraint(
				name = "uk_profile_media_attachment",
				columnNames = {"profile_type", "profile_id", "media_asset_id", "role"}
		),
		indexes = {
				@Index(name = "idx_profile_media_profile", columnList = "profile_type,profile_id"),
				@Index(name = "idx_profile_media_role", columnList = "role")
		}
)
public class ProfileMedia extends BaseEntity {

	// bu medya hangi profile ait?
	@Enumerated(EnumType.STRING)
	@Column(name = "profile_type", nullable = false, length = 32)
	private ProfileType profileType;
	
	// profilin id'si
	@Column(name = "profile_id", nullable = false)
	private UUID profileId;
	
	// gosterilecek medya varligi
	@Column(name = "media_asset_id", nullable = false)
	private UUID mediaAssetId;
	
	// Bu medya profile ekraninda ne amacla kullaniliyor? featured, intro vs
	@Enumerated(EnumType.STRING)
	@Column(name = "role", nullable = false, length = 32)
	private ProfileMediaRole role;
	
	// Ayni role sahip birden fazla medya varsa UI siralamasi icin kullanilir.
	@Column(name = "order_index")
	private Integer orderIndex;
	
}
