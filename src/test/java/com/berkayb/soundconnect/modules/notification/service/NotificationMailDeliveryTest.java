package com.berkayb.soundconnect.modules.notification.service;

import com.berkayb.soundconnect.modules.notification.entity.Notification;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.notification.dto.response.NotificationResponseDto;
import com.berkayb.soundconnect.modules.notification.repository.NotificationRepository;
import com.berkayb.soundconnect.shared.mail.adapter.MailSenderClient;
import com.berkayb.soundconnect.shared.mail.dto.MailSendRequest;
import com.berkayb.soundconnect.shared.mail.enums.MailKind;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.mockito.Mockito.*;

class NotificationMailDeliveryTest {
    @Test void legacyOrDeletedInboxOrErasedSourceNeverReachesMailProvider() {
        var repository=mock(NotificationRepository.class); var policy=mock(NotificationDeliveryPolicy.class);
        var sender=mock(MailSenderClient.class); var service=new NotificationMailDelivery(repository,policy,sender,mock(NotificationService.class));
        service.sendIfCurrent(request(Map.of()));
        UUID id=UUID.randomUUID();
        service.sendIfCurrent(request(Map.of("_notificationId",id.toString())));
        var notification=Notification.builder().sourceEventId(UUID.randomUUID()).recipientId(UUID.randomUUID())
                .type(NotificationType.DM_NEW_MESSAGE).title("Old name").message("Old text").occurredAt(Instant.now()).build();
        notification.setId(id);
        when(repository.findById(id)).thenReturn(Optional.of(notification));
        when(policy.eligible(any())).thenReturn(false);
        service.sendIfCurrent(request(Map.of("_notificationId",id.toString())));
        verifyNoInteractions(sender);
    }
    @Test void currentCorrelatedNotificationCanBeSent() {
        var repository=mock(NotificationRepository.class); var policy=mock(NotificationDeliveryPolicy.class);
        var sender=mock(MailSenderClient.class); var service=new NotificationMailDelivery(repository,policy,sender,mock(NotificationService.class));
        UUID id=UUID.randomUUID();
        var notification=Notification.builder().sourceEventId(UUID.randomUUID())
                .recipientId(UUID.randomUUID()).type(NotificationType.AUTH_EMAIL_VERIFIED).occurredAt(Instant.now()).build();
        notification.setId(id);
        when(repository.findById(id)).thenReturn(Optional.of(notification));
        when(policy.eligible(any())).thenReturn(true);
        var request=request(Map.of("_notificationId",id.toString())); service.sendIfCurrent(request);
        verify(sender).send(request.to(),request.subject(),request.textBody(),request.htmlBody());
    }
    @Test void delayedActorMailUsesCurrentGhostProjectionAndDiscardsOldHtml() {
        var repository=mock(NotificationRepository.class); var policy=mock(NotificationDeliveryPolicy.class);
        var sender=mock(MailSenderClient.class); var identities=mock(NotificationService.class);
        var service=new NotificationMailDelivery(repository,policy,sender,identities);
        UUID id=UUID.randomUUID(), recipient=UUID.randomUUID();
        var notification=Notification.builder().sourceEventId(UUID.randomUUID()).recipientId(recipient)
                .type(NotificationType.SOCIAL_NEW_FOLLOWER).title("Private Name followed you")
                .message("Private Name").occurredAt(Instant.now()).build();
        notification.setId(id);
        when(repository.findById(id)).thenReturn(Optional.of(notification));
        when(policy.eligible(any())).thenReturn(true);
        when(identities.refreshActorIdentityForDelivery(any())).thenReturn(new NotificationResponseDto(id,recipient,
                NotificationType.SOCIAL_NEW_FOLLOWER,"Ghost <listener> seni takip etti","Ghost mesajı",false,
                Instant.now(),Map.of()));
        service.sendIfCurrent(new MailSendRequest("receiver@test.invalid","Private Name",
                "<b>Private Name</b>","Private Name",MailKind.NOTIFICATION,Map.of("_notificationId",id.toString())));
        verify(sender).send(eq("receiver@test.invalid"),eq("[SoundConnect] Ghost <listener> seni takip etti"),
                argThat(text -> text.contains("Ghost mesajı") && !text.contains("Private Name")),isNull());
    }
    private MailSendRequest request(Map<String,Object> params) {
        return new MailSendRequest("old@test.invalid","Snapshot title",null,"Snapshot content",MailKind.NOTIFICATION,params);
    }
}
