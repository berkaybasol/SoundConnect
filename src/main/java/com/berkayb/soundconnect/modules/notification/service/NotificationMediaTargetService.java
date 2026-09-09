package com.berkayb.soundconnect.modules.notification.service;

import com.berkayb.soundconnect.modules.comment.support.CommentTargetAccessGuard;
import com.berkayb.soundconnect.modules.engagement.enums.EngagementTargetType;
import com.berkayb.soundconnect.modules.media.dto.response.MediaResponseDto;
import com.berkayb.soundconnect.modules.media.mapper.MediaAssetMapper;
import com.berkayb.soundconnect.modules.media.repository.MediaAssetRepository;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.notification.repository.NotificationRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class NotificationMediaTargetService {
    private final NotificationRepository notifications;
    private final CommentTargetAccessGuard access;
    private final MediaAssetRepository media;
    private final MediaAssetMapper mapper;

    @Transactional
    public MediaResponseDto resolve(UUID viewerId, UUID notificationId) {
        if (viewerId == null) throw new SoundConnectException(ErrorType.UNAUTHORIZED);
        var notification = notifications.findByIdAndRecipientId(notificationId, viewerId)
                .orElseThrow(() -> new SoundConnectException(ErrorType.NOTIFICATION_NOT_FOUND));
        if ((notification.getType() != NotificationType.SOCIAL_LIKE && notification.getType() != NotificationType.SOCIAL_COMMENT)
                || notification.getPayload() == null || !"MEDIA".equals(notification.getPayload().get("targetType"))) {
            throw new SoundConnectException(ErrorType.ENGAGEMENT_NOT_FOUND);
        }
        UUID id;
        try {
            id = UUID.fromString(String.valueOf(notification.getPayload().get("targetId")));
        } catch (IllegalArgumentException exception) {
            throw new SoundConnectException(ErrorType.ENGAGEMENT_NOT_FOUND);
        }
        access.requireReadable(EngagementTargetType.MEDIA, id);
        return mapper.toDto(media.findById(id).orElseThrow(() -> new SoundConnectException(ErrorType.ENGAGEMENT_NOT_FOUND)));
    }
}
