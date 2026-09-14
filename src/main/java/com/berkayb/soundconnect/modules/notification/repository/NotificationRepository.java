package com.berkayb.soundconnect.modules.notification.repository;

import com.berkayb.soundconnect.modules.notification.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Transactional(readOnly = true)
public interface NotificationRepository extends JpaRepository<Notification, UUID>, NotificationAudienceRepository {

	boolean existsBySourceEventId(UUID sourceEventId);

	Optional<Notification> findBySourceEventId(UUID sourceEventId);
	
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
	
}
