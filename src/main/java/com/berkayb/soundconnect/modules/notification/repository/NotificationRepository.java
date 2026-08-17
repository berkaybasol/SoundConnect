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

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Transactional(readOnly = true)
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

	boolean existsBySourceEventId(UUID sourceEventId);

	Optional<Notification> findBySourceEventId(UUID sourceEventId);
	
	// Olay zamanina gore siralama Pageable ile uygulanir; insert/audit zamani
	// gecikmis broker teslimlerini kullaniciya yeni bir olaymis gibi gostermemelidir.
	Page<Notification> findByRecipientId(UUID recipientId, Pageable pageable);
	
	// kullanicinin tokunmamis bildirim sayisi (badge icin)
	long countByRecipientIdAndReadIsFalse(UUID recipientId);
	
	// ilgili kullaniciya ait belirli bildirimi getir (guvenlik amacli)
	Optional<Notification> findByIdAndRecipientId(UUID id, UUID recipientId);
	
	// bildirim tipine gore filtreleyerek listeleme
	Page<Notification> findByRecipientIdAndTypeIn(UUID recipientId, Collection<NotificationType> types, Pageable pageable);
	
	// hizli cache doldurma icin son 10 kayit (kullanici hizlica son 10 bildirimi gorebilsin diye)
	List<Notification> findTop10ByRecipientIdOrderByOccurredAtDescIdDesc(UUID recipientId);
	
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

	@Modifying
	@Transactional
	@Query(value = """
			update tbl_notification
			set is_read = true
			where recipient_id = :recipientId
			  and type = 'DM_NEW_MESSAGE'
			  and is_read = false
			  and payload ->> 'conversationId' = :conversationId
			""", nativeQuery = true)
	int markUnreadDmNotificationsAsReadByConversation(
			@Param("recipientId") UUID recipientId,
			@Param("conversationId") String conversationId
	);
	
	@Query("select distinct n.recipientId from Notification n where n.createdAt < :cutoff")
	List<UUID> findDistinctRecipientIdsByCreatedAtBefore(@Param("cutoff") LocalDateTime cutoff);
	
	@Modifying
	@Transactional
	@Query("delete from Notification n where n.createdAt < :cutoff")
	int deleteByCreatedAtBefore(@Param("cutoff") LocalDateTime cutoff);
	
	@Modifying
	@Transactional
	@Query("delete from Notification n where n.recipientId = :recipientId")
	int deleteByRecipientId(@Param("recipientId") UUID recipientId);
	
}
