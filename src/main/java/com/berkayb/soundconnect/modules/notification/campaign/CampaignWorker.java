package com.berkayb.soundconnect.modules.notification.campaign;

import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.notification.service.TransactionalNotificationService;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationInboundEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import static com.berkayb.soundconnect.modules.notification.campaign.CampaignContract.*;

/**
 * One bounded transaction owns campaign, occurrence cursor, recipient receipts, inbox and push outbox.
 * No external I/O lease gap: rollback frees its database lock and a restarted worker resumes the cursor.
 */
@Service
@RequiredArgsConstructor
public class CampaignWorker {
    public static final int BATCH_SIZE = 50;
    private final CampaignStore store;
    private final CampaignAccess access;
    private final TransactionalNotificationService notifications;

    record Occurrence(UUID id, Instant started, Instant cutoff, UUID cursor) { }

    @Transactional(timeout = 20)
    public void process(UUID id, Instant now) {
        var claimed = store.jdbc().query(
                "select * from tbl_notification_campaign where id=:id and status='SCHEDULED' for update skip locked",
                Map.of("id", id), store.rowMapper());
        if (claimed.isEmpty()) {
            return;
        }
        var campaign = claimed.getFirst();
        var definition = campaign.definition();
        var schedule = definition.schedule();
        // The exclusive end bounds every batch, including an occurrence resumed after a pause or restart.
        if (schedule.endsAt() != null && !schedule.endsAt().isAfter(now)) {
            complete(id, now);
            return;
        }
        var running = store.jdbc().query(
                "select * from tbl_notification_campaign_occurrence where campaign_id=:id and status='RUNNING'",
                Map.of("id", id),
                (r, n) -> new Occurrence(r.getObject("id", UUID.class), CampaignStore.instant(r, "started_at"),
                        CampaignStore.instant(r, "audience_cutoff"), r.getObject("cursor_user_id", UUID.class)));
        Occurrence occurrence;
        if (running.isEmpty()) {
            if (campaign.next() == null || campaign.next().isAfter(now)) {
                return;
            }
            if (schedule.maxOccurrences() != null && campaign.occurrences() >= schedule.maxOccurrences()) {
                complete(id, now);
                return;
            }
            UUID occurrenceId = UUID.nameUUIDFromBytes((id + ":" + campaign.next()).getBytes(StandardCharsets.UTF_8));
            store.jdbc().update("""
                insert into tbl_notification_campaign_occurrence(id,campaign_id,scheduled_at,started_at,audience_cutoff,status)
                values(:id,:campaign,:scheduled,:now,:now,'RUNNING')
                """, Map.of("id", occurrenceId, "campaign", id,
                    "scheduled", Timestamp.from(campaign.next()), "now", Timestamp.from(now)));
            store.jdbc().update(
                    "update tbl_notification_campaign set occurrences=occurrences+1,updated_at=:now where id=:id",
                    Map.of("id", id, "now", Timestamp.from(now)));
            occurrence = new Occurrence(occurrenceId, now, now, null);
        } else {
            occurrence = running.getFirst();
        }
        var args = new MapSqlParameterSource()
                .addValue("cutoff", Timestamp.from(occurrence.cutoff()))
                .addValue("cursor", occurrence.cursor())
                .addValue("batch", BATCH_SIZE);
        String userFilter = "";
        if (definition.audience().mode() == Mode.USERS) {
            userFilter = " and id in (:users)";
            args.addValue("users", definition.audience().userIds());
        }
        // Snapshot membership ceiling + keyset cursor bound one run despite concurrent registrations.
        var recipients = store.jdbc().queryForList(
                "select id from tbl_user where created_at<=:cutoff and (cast(:cursor as uuid) is null or id>cast(:cursor as uuid))"
                        + userFilter + " order by id limit :batch", args, UUID.class);
        long emitted = 0, skipped = 0;
        for (UUID recipient : recipients) {
            if (!access.eligible(recipient, definition.audience())) {
                skipped++;
                continue;
            }
            UUID event = UUID.nameUUIDFromBytes((occurrence.id() + ":" + recipient).getBytes(StandardCharsets.UTF_8));
            store.jdbc().update(
                    "insert into tbl_notification_campaign_recipient(occurrence_id,recipient_id,event_id) values(:occurrence,:recipient,:event) on conflict do nothing",
                    Map.of("occurrence", occurrence.id(), "recipient", recipient, "event", event));
            notifications.persistInCurrentTransaction(new NotificationInboundEvent(
                    event, recipient, NotificationType.ADMIN_BROADCAST, definition.title(), definition.message(),
                    CampaignEligibility.payload(id, occurrence.id(), definition.target()), false, occurrence.started()));
            boolean persisted = Boolean.TRUE.equals(store.jdbc().queryForObject(
                    "select exists(select 1 from tbl_notification where source_event_id=:event)",
                    Map.of("event", event), Boolean.class));
            if (persisted) {
                emitted++;
            } else {
                skipped++;
            }
        }
        if (!recipients.isEmpty()) {
            store.jdbc().update("update tbl_notification_campaign_occurrence set cursor_user_id=:cursor where id=:id",
                    Map.of("cursor", recipients.getLast(), "id", occurrence.id()));
        }
        store.jdbc().update(
                "update tbl_notification_campaign set recipients=recipients+:recipients,notifications=notifications+:notifications,skipped=skipped+:skipped,updated_at=:now where id=:id",
                Map.of("id", id, "recipients", recipients.size(), "notifications", emitted,
                        "skipped", skipped, "now", Timestamp.from(now)));
        if (recipients.size() < BATCH_SIZE) {
            store.jdbc().update(
                    "update tbl_notification_campaign_occurrence set status='COMPLETED',completed_at=:now where id=:id",
                    Map.of("id", occurrence.id(), "now", Timestamp.from(now)));
            Instant next = CampaignRules.next(schedule, now);
            long count = campaign.occurrences() + (running.isEmpty() ? 1 : 0);
            if (next == null || schedule.maxOccurrences() != null && count >= schedule.maxOccurrences()) {
                complete(id, now);
            } else {
                store.jdbc().update("update tbl_notification_campaign set next_run_at=:next,updated_at=:now where id=:id",
                        Map.of("id", id, "next", Timestamp.from(next), "now", Timestamp.from(now)));
            }
        }
    }

    private void complete(UUID id, Instant now) {
        store.completeRunningOccurrence(id, now);
        store.jdbc().update(
                "update tbl_notification_campaign set status='COMPLETED',next_run_at=null,version=version+1,updated_at=:now where id=:id",
                Map.of("id", id, "now", Timestamp.from(now)));
    }
}
