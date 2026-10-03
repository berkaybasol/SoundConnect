package com.berkayb.soundconnect.modules.application.venueapplication.service;

import com.berkayb.soundconnect.modules.application.venueapplication.entity.VenueApplication;
import com.berkayb.soundconnect.modules.application.venueapplication.enums.ApplicationStatus;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.notification.service.TransactionalNotificationService;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationInboundEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class VenueApplicationDecisionNotifications {
    private final TransactionalNotificationService notifications;

    /** Domain decision, receipt, inbox and queued push jobs commit or roll back together. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void decided(VenueApplication application) {
        if (application.getStatus() != ApplicationStatus.APPROVED && application.getStatus() != ApplicationStatus.REJECTED)
            throw new IllegalArgumentException("A final application decision is required");
        UUID recipient = application.getApplicant().getId();
        var type = application.getStatus() == ApplicationStatus.APPROVED
                ? NotificationType.VENUE_APPLICATION_APPROVED : NotificationType.VENUE_APPLICATION_REJECTED;
        UUID eventId = UUID.nameUUIDFromBytes(("venue-application-decision:" + application.getId() + ":"
                + application.getStatus() + ":" + recipient).getBytes(StandardCharsets.UTF_8));
        notifications.persistInCurrentTransaction(NotificationInboundEvent.builder()
                .eventId(eventId).recipientId(recipient).type(type).title("Mekân başvurusu")
                .message(type == NotificationType.VENUE_APPLICATION_APPROVED
                        ? "Mekân başvurun onaylandı." : "Mekân başvurun reddedildi.")
                .occurredAt(application.getDecisionDate().toInstant(ZoneOffset.UTC)).emailForce(false)
                .payload(Map.of("module", "VENUE_APPLICATION", "applicationId", application.getId().toString(),
                        "applicantUserId", recipient.toString(), "status", application.getStatus().name(),
                        "action", "APPLICATION_" + application.getStatus().name())).build());
    }
}
