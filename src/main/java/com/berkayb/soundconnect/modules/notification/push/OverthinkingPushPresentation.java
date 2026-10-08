package com.berkayb.soundconnect.modules.notification.push;

import com.berkayb.soundconnect.modules.notification.entity.Notification;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.notification.support.OverthinkingNotificationIdentity;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import java.util.Set;

public final class OverthinkingPushPresentation {
    public static final String CAPABILITY="ANDROID_NATIVE_V10", VERSION="ANDROID_OVERTHINKING_V1";
    public static final Set<NotificationType> TYPES=OverthinkingNotificationIdentity.TYPES;
    private OverthinkingPushPresentation() { }
    public static boolean supportsOverthinking(String value) { return CAPABILITY.equals(value) || CustomPushPresentation.CAPABILITY.equals(value); }
    public static boolean eligible(Notification n,NamedParameterJdbcTemplate jdbc) {
        return n!=null && TYPES.contains(n.getType()) && !n.isRead()
            && OverthinkingNotificationIdentity.resolve(jdbc,n.getRecipientId(),n.getId(),true)
                .filter(owned->OverthinkingNotificationIdentity.matches(owned,n)).isPresent();
    }
}
