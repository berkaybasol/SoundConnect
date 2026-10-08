package com.berkayb.soundconnect.modules.notification.push;

import com.berkayb.soundconnect.modules.notification.campaign.CampaignRules;
import com.berkayb.soundconnect.modules.notification.push.transport.PushEnvelope;
import java.util.*;

public final class CustomPushPresentation {
    public static final String CAPABILITY = "ANDROID_NATIVE_V11", VERSION = "ANDROID_CUSTOM_V1";
    private static final Set<String> KEYS = Set.of(
            "notificationId", "recipientId", "type", "presentationVersion", "title", "body", "sentAt", "expiresAt");

    private CustomPushPresentation() { }

    public static boolean valid(PushEnvelope envelope) {
        if (envelope == null || envelope.data() == null) {
            return false;
        }
        var data = envelope.data();
        if (!VERSION.equals(data.get("presentationVersion")) && !"ADMIN_BROADCAST".equals(data.get("type"))) {
            return true;
        }
        try {
            if (!data.keySet().equals(KEYS) || !VERSION.equals(data.get("presentationVersion"))
                    || !"ADMIN_BROADCAST".equals(data.get("type"))
                    || !data.get("title").equals(CampaignRules.text(data.get("title"), 120))
                    || !data.get("body").equals(CampaignRules.text(data.get("body"), 500))) {
                return false;
            }
            if (!UUID.fromString(data.get("notificationId")).toString().equals(data.get("notificationId"))
                    || !UUID.fromString(data.get("recipientId")).toString().equals(data.get("recipientId"))) {
                return false;
            }
            long sent = Long.parseLong(data.get("sentAt")), expires = Long.parseLong(data.get("expiresAt"));
            return sent > 0 && expires > sent && expires - sent <= java.time.Duration.ofDays(28).toMillis()
                    && Long.toString(sent).equals(data.get("sentAt")) && Long.toString(expires).equals(data.get("expiresAt"))
                    && expires == envelope.expiresAt().toEpochMilli();
        } catch (RuntimeException malformed) {
            return false;
        }
    }
}
