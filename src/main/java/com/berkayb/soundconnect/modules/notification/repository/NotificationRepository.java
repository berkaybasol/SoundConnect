package com.berkayb.soundconnect.modules.notification.repository;

import com.berkayb.soundconnect.modules.notification.entity.Notification;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Transactional(readOnly = true)
public interface NotificationRepository extends JpaRepository<Notification, UUID> {
	
	// kullanicinin bildirimlerini yeniden eskiye sayfali getir
	Page<Notification> findByRecipientIdOrderByCreatedAtDesc(UUID recipientId, Pageable pageable);
	
	// kullanicinin tokunmamis bildirim sayisi (badge icin)
	long countByRecipientIdAndReadIsFalse(UUID recipientId);
	
	// ilgili kullaniciya ait belirli bildirimi getir (guvenlik amacli)
	Optional<Notification> findByIdAndRecipientId(UUID id, UUID recipientId);
	
	// bildirim tipine gore filtreleyerek listeleme
	Page<Notification> findByRecipientIdAndTypeInOrderByCreatedAtDesc(UUID recipientId, Collection<NotificationType> types, Pageable pageable);
	
	// hizli cache doldurma icin son 10 kayit (kullanici hizlica son 10 bildirimi gorebilsin diye)
	List<Notification> findTop10ByRecipientIdOrderByCreatedAtDesc(UUID recipientId);
	
	// tek bir bildirimi sahiplik kontrolu yaparak okundu olarak isaretle
	@Modifying // bu anatasyon veriyi update etmek icin (mutating query: degistirilebilir sorgular)
	@Transactional
	@Query("update Notification n set n.read = true where n.id = :id and n.recipientId = :recipientId and n.read = false")
	int markAsRead(@Param("id") UUID id, @Param("recipientId") UUID recipientId);
	
	// kullanicinin tum okunmamislarini okundu olarka isaretle
	@Modifying // bu anatasyon veriyi update etmek icin (mutating query: degistirilebilir sorgular)
	@Transactional
	@Query("update Notification n set n.read = true where n.recipientId = :recipientId and n.read = false")
	int markAllAsRead(@Param("recipientId") UUID recipientId);
	
}