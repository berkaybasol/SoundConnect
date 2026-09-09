package com.berkayb.soundconnect.modules.notification.service;

import com.berkayb.soundconnect.modules.comment.support.CommentTargetAccessGuard;
import com.berkayb.soundconnect.modules.engagement.enums.EngagementTargetType;
import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.mapper.MediaAssetMapper;
import com.berkayb.soundconnect.modules.media.repository.MediaAssetRepository;
import com.berkayb.soundconnect.modules.notification.entity.Notification;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.notification.repository.NotificationRepository;
import com.berkayb.soundconnect.shared.exception.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class NotificationMediaTargetServiceTest {
    @Mock NotificationRepository notifications;
    @Mock CommentTargetAccessGuard access;
    @Mock MediaAssetRepository media;
    @Mock MediaAssetMapper mapper;
    @InjectMocks NotificationMediaTargetService service;
    UUID viewer=UUID.randomUUID(), notificationId=UUID.randomUUID(), assetId=UUID.randomUUID();

    @Test void anotherRecipientsNotificationNeverResolvesMedia() {
        when(notifications.findByIdAndRecipientId(notificationId,viewer)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.resolve(viewer,notificationId)).isInstanceOf(SoundConnectException.class);
        verifyNoInteractions(access,media,mapper);
    }

    @Test void hiddenOrDeletedMediaIsRecheckedBeforeMapping() {
        stub("MEDIA",assetId.toString());
        doThrow(new SoundConnectException(ErrorType.ENGAGEMENT_NOT_FOUND)).when(access).requireReadable(EngagementTargetType.MEDIA,assetId);
        assertThatThrownBy(() -> service.resolve(viewer,notificationId)).isInstanceOf(SoundConnectException.class);
        verifyNoInteractions(media,mapper);
    }

    @Test void freshMediaIsLoadedAfterAccessCheckInsteadOfUsingPayloadUrls() {
        stub("MEDIA",assetId.toString());
        MediaAsset asset=new MediaAsset();
        when(media.findById(assetId)).thenReturn(Optional.of(asset));
        service.resolve(viewer,notificationId);
        var order=inOrder(access,media,mapper);
        order.verify(access).requireReadable(EngagementTargetType.MEDIA,assetId);
        order.verify(media).findById(assetId);
        order.verify(mapper).toDto(asset);
    }

    @Test void malformedTargetCannotTriggerLookup() {
        stub("MEDIA","broken");
        assertThatThrownBy(() -> service.resolve(viewer,notificationId)).isInstanceOf(SoundConnectException.class);
        verifyNoInteractions(access,media,mapper);
    }

    private void stub(String type,String id) {
        when(notifications.findByIdAndRecipientId(notificationId,viewer)).thenReturn(Optional.of(Notification.builder()
                .type(NotificationType.SOCIAL_COMMENT).payload(Map.of("targetType",type,"targetId",id)).build()));
    }
}
