package com.berkayb.soundconnect.modules.notification.service;

import com.berkayb.soundconnect.modules.notification.repository.NotificationRepository;
import com.berkayb.soundconnect.modules.notification.dto.response.NotificationResponseDto;
import com.berkayb.soundconnect.shared.mail.adapter.MailSenderClient;
import com.berkayb.soundconnect.shared.mail.dto.MailSendRequest;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationInboundEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A queued email's identity snapshot cannot outlive its inbox/source/account. */
@Service
@RequiredArgsConstructor
public class NotificationMailDelivery {
    private final NotificationRepository notifications;
    private final NotificationDeliveryPolicy deliveryPolicy;
    private final MailSenderClient sender;
    private final NotificationService notificationService;

    @Transactional
    public void sendIfCurrent(MailSendRequest request) {
        var id=NotificationDeliveryPolicy.uuid(request.params(),"_notificationId");
        // Legacy uncorrelated mail cannot establish that its account/source is
        // still eligible. A new notification can enqueue a correlated email.
        if(id==null) return;
        var notification=notifications.findById(id).orElse(null);
        if(notification==null) return;
        var event=new NotificationInboundEvent(notification.getSourceEventId(),notification.getRecipientId(),
                notification.getType(),notification.getTitle(),notification.getMessage(),notification.getPayload(),true,
                notification.getOccurredAt());
        if(!deliveryPolicy.eligible(event)) return;
        if(NotificationService.requiresActorIdentityRefresh(notification.getType())) {
            var current=notificationService.refreshActorIdentityForDelivery(new NotificationResponseDto(
                    notification.getId(), notification.getRecipientId(), notification.getType(),
                    notification.getTitle(), notification.getMessage(), notification.isRead(),
                    notification.getOccurredAt(), notification.getPayload()));
            if(current==null) return;
            String title=current.title()==null || current.title().isBlank()
                    ? current.type().getDefaultTitle() : current.title();
            String subject="[SoundConnect] " + title;
            String text=subject + "\n\n" + (current.message()==null ? "" : current.message() + "\n\n")
                    + "Bu e-posta SoundConnect tarafından otomatik gönderildi.";
            // The notification producer uses plain text; never reuse HTML or
            // identity snapshots from an earlier queued mail job.
            sender.send(request.to(),subject,text,null);
            return;
        }
        sender.send(request.to(),request.subject(),request.textBody(),request.htmlBody());
    }
}
