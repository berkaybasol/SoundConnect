package com.berkayb.soundconnect.modules.notification.repository;

import com.berkayb.soundconnect.modules.notification.entity.Notification;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Every recipient-facing read/mutation uses the same current audience boundary. */
public interface NotificationAudienceRepository {
    Page<Notification> findByRecipientId(UUID recipientId, Pageable pageable);
    Page<Notification> findByRecipientIdAndTypeIn(UUID recipientId, Collection<NotificationType> types, Pageable pageable);
    List<Notification> findTop10ByRecipientIdOrderByOccurredAtDescIdDesc(UUID recipientId);
    Optional<Notification> findByIdAndRecipientId(UUID id, UUID recipientId);
    long countByRecipientIdAndReadIsFalse(UUID recipientId);
    @Transactional int markAsRead(UUID id, UUID recipientId);
    @Transactional int markAllAsRead(UUID recipientId);
    @Transactional int deleteByRecipientId(UUID recipientId);
}
