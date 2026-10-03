package com.berkayb.soundconnect.modules.notification.push;

import com.berkayb.soundconnect.modules.notification.entity.Notification;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.notification.service.TableNotificationTargetService;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import java.util.*;

/** V8 adds TABLE; only occurrence identity and fixed display copy cross FCM. */
public final class TablePushPresentation {
    public static final String CAPABILITY = "ANDROID_NATIVE_V8";
    public static final String VERSION = "ANDROID_TABLE_V1";
    public static final Set<NotificationType> TYPES = Set.of(
            NotificationType.TABLE_JOIN_REQUEST_RECEIVED, NotificationType.TABLE_JOIN_REQUEST_APPROVED,
            NotificationType.TABLE_JOIN_REQUEST_REJECTED, NotificationType.TABLE_PARTICIPANT_LEFT,
            NotificationType.TABLE_REMOVED, NotificationType.TABLE_CANCELLED, NotificationType.TABLE_EXPIRED);
    private TablePushPresentation() { }
    public static boolean supportsTable(String capability) { return CAPABILITY.equals(capability) || CollabPushPresentation.supportsCollab(capability); }
    public static boolean valid(String type, String variant) {
        if ("TABLE_CANCELLED".equals(type))
            return Set.of("OWNER_CANCELLED", "OWNER_JOINED_ANOTHER_TABLE").contains(variant == null ? "" : variant);
        return "DEFAULT".equals(variant) && TYPES.stream().anyMatch(t -> t.name().equals(type));
    }

    /** Uses the existing exact owned resolver, including receipt/source/role and
     * cycle/legacy semantics. A valid historical RESULT remains deliverable;
     * it cannot become the current application's chat or decision target. */
    public static Optional<String> resolve(Notification n, NamedParameterJdbcTemplate jdbc) {
        if (n == null || !TYPES.contains(n.getType()) || n.getId() == null || n.getRecipientId() == null
                || n.getSourceEventId() == null || n.getOccurredAt() == null || n.isRead()) return Optional.empty();
        // Bind the supplied planning/send row to the persisted occurrence. The
        // resolver checks exact JSON equality with durable source evidence.
        if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from tbl_notification n
                  join tbl_table_notification_event e on e.event_id=n.source_event_id
                  where n.id=:id and n.recipient_id=:recipient and n.source_event_id=:event
                    and n.type=:type and not n.is_read and n.occurred_at=e.occurred_at)
                """, Map.of("id", n.getId(), "recipient", n.getRecipientId(), "event", n.getSourceEventId(),
                "type", n.getType().name()), Boolean.class))) return Optional.empty();
        try {
            var target = new TableNotificationTargetService(jdbc).resolve(n.getRecipientId(), n.getId());
            if (target.read() || target.type() != n.getType()) return Optional.empty();
            String variant = n.getType() == NotificationType.TABLE_CANCELLED ? target.reason() : "DEFAULT";
            return valid(n.getType().name(), variant) ? Optional.of(variant) : Optional.empty();
        } catch (SoundConnectException unavailable) {
            return Optional.empty();
        }
    }
}
