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

	@Query(value = """
			select n.id from tbl_notification n
			where n.id in (:ids) and n.type='OVERTHINKING_REVEAL_REQUEST_APPROVED'
			  and exists (select 1 from tbl_user_block b where b.unblocked_at is null
				and ((b.blocker_id=n.recipient_id and cast(b.blocked_id as text)=n.payload->>'authorId')
				  or (b.blocked_id=n.recipient_id and cast(b.blocker_id as text)=n.payload->>'authorId')))
			""", nativeQuery = true)
	java.util.Set<UUID> findHiddenRevealApprovals(@Param("ids") List<UUID> ids);

	@Modifying
	@Transactional
	@Query(value = """
			update tbl_notification
			set is_read = true
			where recipient_id = :recipientId
			  and type = 'DM_NEW_MESSAGE'
			  and is_read = false
			  and payload ->> 'messageId' = :messageId
			""", nativeQuery = true)
	int markUnreadDmNotificationsAsReadByMessage(
			@Param("recipientId") UUID recipientId,
			@Param("messageId") String messageId);

	Optional<Notification> findBySourceEventId(UUID sourceEventId);

	/** The caller holds the DM conversation lock before deleting its source. */
	@Modifying(flushAutomatically = true)
	@Transactional
	@Query(value = "delete from tbl_notification where type = 'DM_NEW_MESSAGE' and payload ->> 'messageId' = :messageId", nativeQuery = true)
	int deleteDmMessageNotifications(@Param("messageId") String messageId);

	@Modifying(flushAutomatically = true)
	@Transactional
	@Query(value = "delete from tbl_notification where type = 'DM_NEW_MESSAGE' and payload ->> 'conversationId' = :conversationId", nativeQuery = true)
	int deleteDmConversationNotifications(@Param("conversationId") String conversationId);
	
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
