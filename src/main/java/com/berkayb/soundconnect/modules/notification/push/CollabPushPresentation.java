package com.berkayb.soundconnect.modules.notification.push;

import com.berkayb.soundconnect.modules.notification.entity.Notification;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.notification.support.CollabNotificationIdentity;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import java.util.*;

public final class CollabPushPresentation {
    public static final String CAPABILITY="ANDROID_NATIVE_V9", VERSION="ANDROID_COLLAB_V1";
    public static final Set<NotificationType> TYPES=CollabNotificationIdentity.TYPES;
    private CollabPushPresentation() { }
    public static boolean supportsCollab(String value) { return CAPABILITY.equals(value) || OverthinkingPushPresentation.supportsOverthinking(value); }
    public static boolean valid(String type,String variant) {
        if ("COLLAB_REPORT_RESOLVED".equals(type)) return Set.of("REMOVE_LISTING","DISMISS").contains(variant==null?"":variant);
        return "DEFAULT".equals(variant) && TYPES.stream().anyMatch(t->t.name().equals(type));
    }
    public static Optional<String> resolve(Notification n,NamedParameterJdbcTemplate jdbc) {
        if (n==null || !TYPES.contains(n.getType()) || n.isRead()) return Optional.empty();
        return CollabNotificationIdentity.resolve(jdbc,n.getRecipientId(),n.getId(),true)
            .filter(owned->CollabNotificationIdentity.matches(owned,n))
            .map(owned->n.getType()==NotificationType.COLLAB_REPORT_RESOLVED
                ? (String)owned.notification().payload().get("decision") : "DEFAULT");
    }
}
