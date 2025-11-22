package com.berkayb.soundconnect.modules.collab.entity;

import com.berkayb.soundconnect.modules.collab.enums.CollabCategory;
import com.berkayb.soundconnect.modules.collab.enums.CollabRole;
import com.berkayb.soundconnect.modules.instrument.entity.Instrument;
import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.checkerframework.checker.units.qual.C;
import org.hibernate.annotations.BatchSize;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@Getter
@Setter
@Entity
@Table(name = "tbl_collab")
public class Collab extends BaseEntity {
	
	// ilani acan kullanici
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "owner_user_id", nullable = false)
	private User owner;
	
	/*
	ilani acan kullanicinin sistemdeki rolu.
	bu bilgi UI da ve filtrelemede isimize cok yaricak
	 */
	@Enumerated(EnumType.STRING)
	@Column(name = "owner_role", nullable = false, length = 40)
	private CollabRole ownerRole;
	
	// ilanin hedefledigi roller
	@ElementCollection(fetch = FetchType.LAZY)
	@BatchSize(size = 20)
	@CollectionTable(name = "collab_target_roles", joinColumns = @JoinColumn(name = "collab_id")
	)
	@Enumerated(EnumType.STRING)
	@Column(name = "role", length = 40)
	@Builder.Default
	private Set<CollabRole> targetRoles = new HashSet<>();
	
	// ilanin kategorisi
	@Enumerated(EnumType.STRING)
	@Column(name = "category", nullable = false, length = 40)
	private CollabCategory category;
	
	
	// ilan basligi
	@Column(nullable = false, length = 128)
	private String title;
	
	// ilan aciklama
	@Column(length = 2048)
	private String description;
	
	// ucret (mekanlar vs icin opsiyonel)
	private Integer price;
	
	// gunluk ilanlar. daily = true -> expirationTIme zorunl olur.
	@Column(nullable = false)
	@Builder.Default
	private boolean daily = false;
	
	// daily ilanlar icin kapanma zamani Redis TTL entegrasyonu ile otomatik kapanma burda yonetilcek
	private LocalDateTime expirationTime;
	
	// Ilan lokasyonu
	@JoinColumn(name = "city_id")
	@ManyToOne(fetch = FetchType.LAZY)
	private City city;
	
	
	/**
	 * Yeni slot sistemi — 1 collab N adet slot’a sahip olabilir.
	 * (ör: 2 gitar + 1 bas + 1 davul)
	 */
	@OneToMany(
			mappedBy = "collab",
			cascade = CascadeType.ALL,
			orphanRemoval = true,
			fetch = FetchType.LAZY
	)
	@Builder.Default
	private Set<CollabRequiredSlot> requiredSlots = new HashSet<>();
	
	
	// methodlar
	public int getTotalRequired() {
		return requiredSlots.stream()
		                    .mapToInt(CollabRequiredSlot::getRequiredCount)
		                    .sum();
	}
	
	public int getTotalFilled() {
		return requiredSlots.stream()
		                    .mapToInt(CollabRequiredSlot::getFilledCount)
		                    .sum();
	}
	
	public boolean hasOpenSlots() {
		return requiredSlots.stream().anyMatch(CollabRequiredSlot::hasOpenSlot);
	}
	
	public int getRemainingSlotCount() {
		return getTotalRequired() - getTotalFilled();
	}
}