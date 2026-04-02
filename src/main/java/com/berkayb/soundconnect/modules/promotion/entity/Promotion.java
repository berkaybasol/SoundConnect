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
	
	@Column(length = 500)
	private String description;
	
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "media_asset_id", nullable = false)
	private MediaAsset mediaAsset; // promotion gorseli icin media kaydi
	
	@Column(length = 500)
	private String redirectUrl; // promotion tiklandiginda yonlendirme adresi
	
	@Column(nullable = false)
	@Builder.Default
	private Integer priority = 0; // promotion gosterim onceligi UI da yana kaydirmali falan yaparsak diye
	
	private LocalDateTime startDate;
	
	private LocalDateTime endDate;
 
	
}