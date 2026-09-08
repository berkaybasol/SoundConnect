package com.berkayb.soundconnect.modules.notification.repository;

import com.berkayb.soundconnect.modules.notification.entity.NotificationReceipt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.time.LocalDateTime;

@Transactional(readOnly = true)
public interface NotificationReceiptRepository extends JpaRepository<NotificationReceipt, UUID> {
    /** Insert is the shared concurrent-delivery fence, not a check-then-insert. */
    @Modifying
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = """
            insert into tbl_notification_receipt(source_event_id, recipient_id, recorded_at)
            values (:sourceEventId, :recipientId, current_timestamp)
            on conflict do nothing
            """, nativeQuery = true)
    int claim(@Param("sourceEventId") UUID sourceEventId, @Param("recipientId") UUID recipientId);

    /** Preserve migrated/legacy receipts before erasing content, without loading payloads. */
    @Modifying(flushAutomatically = true)
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = """
            insert into tbl_notification_receipt(source_event_id, recipient_id, recorded_at)
            select source_event_id, recipient_id, current_timestamp from tbl_notification
            where id = :notificationId and recipient_id = :recipientId and source_event_id is not null
            on conflict do nothing
            """, nativeQuery = true)
    int retainForNotification(@Param("notificationId") UUID notificationId, @Param("recipientId") UUID recipientId);

    @Modifying(flushAutomatically = true)
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = """
            insert into tbl_notification_receipt(source_event_id, recipient_id, recorded_at)
            select source_event_id, recipient_id, current_timestamp from tbl_notification
            where recipient_id = :recipientId and source_event_id is not null
            on conflict do nothing
            """, nativeQuery = true)
    int retainForRecipient(@Param("recipientId") UUID recipientId);

    @Modifying(flushAutomatically = true)
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = """
            insert into tbl_notification_receipt(source_event_id, recipient_id, recorded_at)
            select source_event_id, recipient_id, current_timestamp from tbl_notification
            where created_at < :cutoff and source_event_id is not null
            on conflict do nothing
            """, nativeQuery = true)
    int retainBeforeCutoff(@Param("cutoff") LocalDateTime cutoff);
}
