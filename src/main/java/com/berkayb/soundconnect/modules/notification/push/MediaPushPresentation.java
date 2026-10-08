package com.berkayb.soundconnect.modules.notification.push;

import com.berkayb.soundconnect.modules.notification.entity.Notification;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.notification.service.NotificationDeliveryPolicy;
import com.berkayb.soundconnect.modules.notification.support.MediaNotificationIdentity;
import com.berkayb.soundconnect.modules.notification.support.NotificationAudiencePolicy;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Closed, identity-free MEDIA wire; eligibility is re-read before delivery. */
public final class MediaPushPresentation {
    public static final String CAPABILITY = "ANDROID_NATIVE_V6";
    public static final String VERSION = "ANDROID_MEDIA_V1";
    public static final Set<NotificationType> TYPES = Set.of(NotificationType.SOCIAL_LIKE, NotificationType.SOCIAL_COMMENT);
    private MediaPushPresentation() { }
    public static boolean supportsMedia(String capability) { return CAPABILITY.equals(capability) || BandPushPresentation.supportsBand(capability); }
    public static boolean valid(String type, String variant) {
        return "DEFAULT".equals(variant) && TYPES.stream().anyMatch(t -> t.name().equals(type));
    }

    public static boolean eligible(Notification n, NamedParameterJdbcTemplate jdbc) {
        if (!MediaNotificationIdentity.applies(n.getType(), n.getPayload())
                || !"SOCIAL".equals(n.getPayload().get("module"))) return false;
        UUID id = NotificationDeliveryPolicy.uuid(n.getPayload(), "targetId");
        if (id == null) return false;
        var owners = jdbc.query("select owner_type,owner_id from tbl_media_asset where id=:id",
                Map.of("id",id), (rs,row) -> Map.entry(rs.getString(1), rs.getObject(2,UUID.class)));
        if (owners.isEmpty()) return false;
        var owner = owners.getFirst();
        // Same privacy -> media lock ordering as CommentTargetAccessGuard.
        boolean listenerOwner = "LISTENER_PROFILE".equals(owner.getKey());
        if (listenerOwner || "USER".equals(owner.getKey())) {
            var visibility = jdbc.query("""
                    select visibility_choice_completed and visibility_mode='STANDARD'
                    from "tbl_listener-profile"
                    where (:profile and id=:id) or (not :profile and user_id=:id) for share
                    """, Map.of("profile",listenerOwner,"id",owner.getValue()), (rs,row)->rs.getBoolean(1));
            if (visibility.isEmpty() ? listenerOwner : !visibility.getFirst()) return false;
        }
        boolean listener = Boolean.TRUE.equals(jdbc.queryForObject(NotificationAudiencePolicy.LISTENER_SQL,
                Map.of("recipient",n.getRecipientId()),Boolean.class));
        return !jdbc.query("""
                select id from tbl_media_asset where id=:id and owner_type=:ownerType and owner_id=:ownerId
                  and status='READY' and visibility='PUBLIC'
                  and (not :listener or (content_audience='MAINSTAGE' and owner_type<>'STUDIO_PROFILE'))
                  and (nullif(trim(playback_url),'') is not null or nullif(trim(source_url),'') is not null)
                for share
                """,Map.of("id",id,"ownerType",owner.getKey(),"ownerId",owner.getValue(),"listener",listener),
                (rs,row)->rs.getObject(1)).isEmpty();
    }
}
