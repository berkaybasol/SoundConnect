package com.berkayb.soundconnect.modules.engagement.service;

import com.berkayb.soundconnect.modules.engagement.enums.EngagementTargetType;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.notification.service.TransactionalNotificationService;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationInboundEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Called only after the engagement access guard and mutation, inside their transaction. */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class MediaEngagementNotificationService {
    private final JdbcTemplate jdbc;
    private final TransactionalNotificationService inbox;

    public void liked(UUID actorId, EngagementTargetType type, UUID targetId) {
        if (type == EngagementTargetType.MEDIA) notifyOwner(actorId, targetId, null);
    }

    public void commented(UUID actorId, EngagementTargetType type, UUID targetId, UUID commentId) {
        if (type == EngagementTargetType.MEDIA) notifyOwner(actorId, targetId, Objects.requireNonNull(commentId));
    }

    private void notifyOwner(UUID actorId, UUID assetId, UUID commentId) {
        var owners = jdbc.query("select owner_type,owner_id from tbl_media_asset where id=?",
                (rs, row) -> new Owner(rs.getString(1), rs.getObject(2, UUID.class)), assetId);
        if (owners.isEmpty()) return;
        Owner owner = owners.getFirst();
        String recipientQuery = switch (owner.type()) {
            case "USER" -> "select id from tbl_user where id=?";
            case "MUSICIAN_PROFILE" -> "select user_id from tbl_musician_profile where id=?";
            case "PRODUCER_PROFILE" -> "select user_id from tbl_producer_profile where id=?";
            case "ORGANIZER_PROFILE" -> "select user_id from tbl_organizer_profile where id=?";
            case "STUDIO_PROFILE" -> "select user_id from tbl_studio_profile where id=?";
            case "LISTENER_PROFILE" -> "select user_id from \"tbl_listener-profile\" where id=?";
            case "VENUE" -> "select owner_id from tbl_venues where id=?";
            case "VENUE_PROFILE" -> "select v.owner_id from tbl_venue_profile p join tbl_venues v on v.id=p.venue_id where p.id=?";
            case "BAND" -> "select user_id from tbl_band_member where band_id=? and status='ACTIVE' and band_role in ('FOUNDER','MANAGER')";
            default -> null;
        };
        if (recipientQuery == null) return;
        List<UUID> recipients = jdbc.query("select id from tbl_user where status='ACTIVE' and email_verified=true and id in ("
                        + recipientQuery + ") order by id", (rs, row) -> rs.getObject(1, UUID.class), owner.id());
        if (recipients.isEmpty() || recipients.contains(actorId)) return;

        // Canonical username is also the permitted ghost identity. Never snapshot
        // alternate profile names, avatars, comment text or media access URLs.
        String username = jdbc.queryForObject("select user_name from tbl_user where id=?", String.class, actorId);
        String name = username == null || username.isBlank() ? "Bir kullanıcı" : username.strip();
        if (name.length() > 80) name = name.substring(0, 80);
        NotificationType type = commentId == null ? NotificationType.SOCIAL_LIKE : NotificationType.SOCIAL_COMMENT;
        for (UUID recipient : recipients) {
            String identity = commentId == null ? "like:" + actorId + ":" + assetId : "comment:" + commentId;
            UUID eventId = UUID.nameUUIDFromBytes(("media-engagement:" + identity + ":" + recipient).getBytes(StandardCharsets.UTF_8));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("module", "SOCIAL");
            payload.put("targetType", "MEDIA");
            payload.put("targetId", assetId.toString());
            if (commentId != null) payload.put("commentId", commentId.toString());
            inbox.persistInCurrentTransaction(NotificationInboundEvent.builder()
                    .eventId(eventId).recipientId(recipient).type(type)
                    .title(name + (commentId == null ? " içeriğini beğendi" : " içeriğine yorum yaptı"))
                    .message("Bildirime dokunarak içeriği açabilirsin.")
                    .payload(payload).emailForce(false).occurredAt(Instant.now()).build());
        }
    }

    private record Owner(String type, UUID id) {}
}
