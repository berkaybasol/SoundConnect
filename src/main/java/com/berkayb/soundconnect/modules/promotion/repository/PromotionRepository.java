package com.berkayb.soundconnect.modules.promotion.repository;

import com.berkayb.soundconnect.modules.promotion.entity.Promotion;
import com.berkayb.soundconnect.modules.promotion.enums.PromotionPlacement;
import com.berkayb.soundconnect.modules.promotion.enums.PromotionStatus;
import com.berkayb.soundconnect.modules.promotion.enums.PromotionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface PromotionRepository extends JpaRepository<Promotion, UUID> {
	
	// belirli bir placement alanindaki yayina uygun kayitlari getirir.
	@Query("""
			SELECT p
			FROM Promotion p
			WHERE p.placement = :placement
			  AND p.status = :status
			  AND (p.startDate IS NULL OR p.startDate <= :now)
			  AND (p.endDate IS NULL OR p.endDate >= :now)
			ORDER BY p.priority DESC, p.createdAt DESC
			""")
	List<Promotion> findAllDisplayableByPlacement(
			@Param("placement") PromotionPlacement placement,
			@Param("status") PromotionStatus status,
			@Param("now") LocalDateTime now
	);
	
	// admin panel tarafinda belirli bir placement alanindaki tum promotion kayitlarini listele
	List<Promotion> findAllByPlacementOrderByPriorityDescCreatedAtDesc(PromotionPlacement placement);
	
	// statusa gore siralama
	List<Promotion> findAllByStatusOrderByCreatedAtDesc(PromotionStatus status);
	
	// promotion tiplerine gore siralama
	List<Promotion> findAllByTypeOrderByCreatedAtDesc(PromotionType type);
	
	// placement + status kombinasyonundaki kayitlari sirali sekilde getir
	List<Promotion> findAllByPlacementAndStatusOrderByPriorityDescCreatedAtDesc(PromotionPlacement placement, PromotionStatus status);
	
}