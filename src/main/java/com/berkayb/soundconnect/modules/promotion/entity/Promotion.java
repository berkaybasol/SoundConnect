package com.berkayb.soundconnect.modules.promotion.entity;

import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.promotion.enums.PromotionPlacement;
import com.berkayb.soundconnect.modules.promotion.enums.PromotionStatus;
import com.berkayb.soundconnect.modules.promotion.enums.PromotionType;
import com.berkayb.soundconnect.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileType;

@Entity
@Table(name = "tlb_promotion")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@ToString(callSuper = true, exclude = "mediaAsset")
@EqualsAndHashCode(callSuper = true, exclude = "mediaAsset")
public class Promotion extends BaseEntity {
	
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 50)
	private PromotionType type; // promotion iceriginin turu
	
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 100)
	private PromotionPlacement placement;
	
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private PromotionStatus status;
	
	@Column(nullable = false, length = 150)
	private String title;
	
	@Column(length = 5000)
	private String description;
	
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "media_asset_id")
	private MediaAsset mediaAsset; // promotion gorseli icin media kaydi
	
	@Column(length = 500)
	private String redirectUrl; // promotion tiklandiginda yonlendirme adresi
	
	@Column(nullable = false)
	@Builder.Default
	private Integer priority = 0; // promotion gosterim onceligi UI da yana kaydirmali falan yaparsak diye
	
	private LocalDateTime startDate;
	
	private LocalDateTime endDate;

	@Version
	@Column(nullable = false)
	@Builder.Default
	private long version = 0;

	private Instant firstPublishedAt;
	private Instant archivedAt;
	private UUID createdBy;
	private UUID updatedBy;

	@ElementCollection
	@CollectionTable(name = "tbl_promotion_audience", joinColumns = @JoinColumn(name = "promotion_id"),
			uniqueConstraints = @UniqueConstraint(name = "uq_promotion_audience", columnNames = {"promotion_id", "profile_type"}))
	@Enumerated(EnumType.STRING)
	@Column(name = "profile_type", nullable = false, length = 30)
	@Builder.Default
	private Set<ProfileType> targetProfiles = new HashSet<>();
 
	
}
