package com.berkayb.soundconnect.modules.notification.support;

import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import java.util.*;

/** Versioned actor reference, never an identity snapshot. Legacy rows remain anonymous. */
public final class MediaNotificationIdentity {
    private MediaNotificationIdentity() { }
    public static final String MESSAGE = "Bildirime dokunarak içeriği açabilirsin.";
    private static final Set<String> SNAPSHOT_KEYS = Set.of("actorName", "actorUsername", "actorAvatarUrl",
            "actorProfilePictureUrl", "actorVisibilityMode", "senderName", "senderUsername", "senderAvatarUrl",
            "senderVisibilityMode", "username", "avatarUrl", "profilePictureUrl", "displayName", "profileName");

    public static boolean applies(NotificationType type, Map<String,Object> payload) {
        return (type == NotificationType.SOCIAL_LIKE || type == NotificationType.SOCIAL_COMMENT)
                && payload != null && "MEDIA".equals(payload.get("targetType"));
    }

    public static Optional<UUID> actorId(Map<String,Object> payload) {
        if (payload == null || !(payload.get("mediaIdentityVersion") instanceof Number version)
                || !"1".equals(version.toString())) return Optional.empty();
        Object raw = payload.get("actorId");
        if (raw instanceof UUID id) return Optional.of(id);
        if (!(raw instanceof String text)) return Optional.empty();
        try {
            UUID id = UUID.fromString(text);
            // UUID.fromString also accepts short groups; those are not this wire contract.
            return id.toString().equalsIgnoreCase(text) ? Optional.of(id) : Optional.empty();
        } catch (IllegalArgumentException invalid) { return Optional.empty(); }
    }

    public static String title(NotificationType type, String name) {
        String actor = name == null || name.isBlank() ? "Bir kullanıcı" : name.strip();
        if (actor.length() > 80) actor = actor.substring(0,80);
        return actor + (type == NotificationType.SOCIAL_LIKE ? " içeriğini beğendi" : " içeriğine yorum yaptı");
    }

    public static Map<String,Object> payload(Map<String,Object> original) {
        var result = new LinkedHashMap<String,Object>(original);
        SNAPSHOT_KEYS.forEach(result::remove);
        Optional<UUID> actor = actorId(original);
        if (actor.isPresent()) {
            result.put("actorId",actor.get().toString()); result.put("mediaIdentityVersion",1);
        } else {
            result.remove("actorId"); result.put("mediaIdentityVersion",0);
        }
        return Collections.unmodifiableMap(result);
    }
}
