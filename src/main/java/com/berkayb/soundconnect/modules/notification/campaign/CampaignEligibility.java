package com.berkayb.soundconnect.modules.notification.campaign;

import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationInboundEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.*;
import static com.berkayb.soundconnect.modules.notification.campaign.CampaignContract.*;

/** Persisted occurrence and recipient proof, independently of sender payloads or JWT role snapshots. */
@Component
@RequiredArgsConstructor
public class CampaignEligibility {
    private final CampaignStore store;
    private final CampaignAccess access;

    public boolean eligible(NotificationInboundEvent event) {
        if (event == null || event.type() != NotificationType.ADMIN_BROADCAST
                || event.eventId() == null || event.recipientId() == null) {
            return false;
        }
        var rows = store.jdbc().query("""
            select c.* from tbl_notification_campaign c
            join tbl_notification_campaign_occurrence o on o.campaign_id=c.id
            join tbl_notification_campaign_recipient r on r.occurrence_id=o.id
            where r.event_id=:event and r.recipient_id=:recipient
              and c.status in ('SCHEDULED','COMPLETED') and o.status in ('RUNNING','COMPLETED')
              and o.id::text=:occurrence and c.id::text=:campaign
            """, Map.of("event", event.eventId(), "recipient", event.recipientId(),
                "campaign", value(event, "campaignId"), "occurrence", value(event, "occurrenceId")), store.rowMapper());
        if (rows.isEmpty()) {
            return false;
        }
        var row = rows.getFirst();
        return payload(row.id(), UUID.fromString(value(event, "occurrenceId")), row.definition().target()).equals(event.payload())
                && access.eligible(event.recipientId(), row.definition().audience());
    }

    static Map<String, Object> payload(UUID campaign, UUID occurrence, Target target) {
        var result = new LinkedHashMap<String, Object>();
        result.put("campaignId", campaign.toString());
        result.put("occurrenceId", occurrence.toString());
        result.put("targetKind", target.kind().name());
        if (target.targetId() != null) {
            result.put("targetId", target.targetId().toString());
        }
        return Map.copyOf(result);
    }

    private static String value(NotificationInboundEvent e, String key) {
        return e.payload() == null ? "" : String.valueOf(e.payload().getOrDefault(key, ""));
    }
}
