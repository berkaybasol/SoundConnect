package com.berkayb.soundconnect.modules.notification.push;

import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.notification.entity.Notification;
import com.berkayb.soundconnect.modules.notification.service.NotificationDeliveryPolicy;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import java.util.Map;
import java.util.Set;

/** Closed identity-free wire. Historical follow occurrences do not require a live relationship. */
public final class FollowPushPresentation {
    public static final String CAPABILITY = "ANDROID_NATIVE_V5";
    public static final String VERSION = "ANDROID_FOLLOW_V1";
    public static final Set<NotificationType> TYPES = Set.of(
            NotificationType.SOCIAL_NEW_FOLLOWER, NotificationType.SOCIAL_NEW_BAND_FOLLOWER);
    private FollowPushPresentation() { }
    public static boolean supportsFollow(String capability) { return CAPABILITY.equals(capability) || MediaPushPresentation.supportsMedia(capability); }
    public static boolean valid(String type, String variant) {
        return "DEFAULT".equals(variant) && TYPES.stream().anyMatch(t -> t.name().equals(type));
    }
    public static boolean eligible(Notification n, NamedParameterJdbcTemplate jdbc) {
        var follower=NotificationDeliveryPolicy.uuid(n.getPayload(),"followerId");
        if(follower==null || n.getPayload()==null) return false;
        boolean band=n.getType()==NotificationType.SOCIAL_NEW_BAND_FOLLOWER;
        if(!(band?"NEW_BAND_FOLLOWER":"NEW_FOLLOWER").equals(n.getPayload().get("action"))) return false;
        // The shared delivery policy already holds the account/erasure fence.
        if(jdbc.query("select id from tbl_user where id=:id and erased_at is null for share",
                Map.of("id",follower),(rs,row)->rs.getObject(1)).isEmpty()) return false;
        if(!band) return true;
        var bandId=NotificationDeliveryPolicy.uuid(n.getPayload(),"bandId");
        return bandId!=null && !jdbc.query("select id from tbl_band where id=:id for share",
                Map.of("id",bandId),(rs,row)->rs.getObject(1)).isEmpty();
    }
}
