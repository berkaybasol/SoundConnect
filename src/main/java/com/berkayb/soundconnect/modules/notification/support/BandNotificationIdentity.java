package com.berkayb.soundconnect.modules.notification.support;

import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import java.util.*;

/** Only exact producer references are identities. Version zero is anonymous legacy history. */
public final class BandNotificationIdentity {
    private BandNotificationIdentity() { }
    public static final Set<NotificationType> TYPES = Set.of(NotificationType.BAND_INVITE_RECEIVED,
            NotificationType.BAND_INVITE_ACCEPTED, NotificationType.BAND_INVITE_REJECTED,
            NotificationType.BAND_MEMBER_REMOVED, NotificationType.BAND_MEMBER_LEFT);
    public static final String MESSAGE = "Grup bildiriminin ayrıntılarını uygulamada görebilirsin.";
    public static boolean applies(NotificationType type) { return type != null && TYPES.contains(type); }
    public static String action(NotificationType type) { return type.name().substring(5); }
    public static String actorKey(NotificationType type) {
        return switch (type) {
            case BAND_INVITE_RECEIVED -> "inviterId";
            case BAND_MEMBER_REMOVED -> "requesterId";
            default -> "memberId";
        };
    }
    public static Optional<UUID> uuid(Map<String,Object> value, String key) {
        Object raw = value == null ? null : value.get(key);
        if (raw instanceof UUID id) return Optional.of(id);
        if (!(raw instanceof String text)) return Optional.empty();
        try {
            UUID id = UUID.fromString(text);
            return id.toString().equalsIgnoreCase(text) ? Optional.of(id) : Optional.empty();
        } catch (IllegalArgumentException invalid) { return Optional.empty(); }
    }
    public static Optional<UUID> actorId(NotificationType type, Map<String,Object> value) {
        if (!applies(type) || value == null || !(value.get("bandIdentityVersion") instanceof Number version)
                || !"1".equals(version.toString()) || !"BAND".equals(value.get("module"))
                || !action(type).equals(value.get("action")) || uuid(value,"bandId").isEmpty()) return Optional.empty();
        return uuid(value,actorKey(type));
    }
    public static Map<String,Object> payload(NotificationType type, Map<String,Object> value) {
        var result = new LinkedHashMap<String,Object>();
        result.put("module","BAND");
        uuid(value,"bandId").ifPresent(id -> result.put("bandId",id.toString()));
        uuid(value,"invitationId").ifPresent(id -> result.put("invitationId",id.toString()));
        if (value != null && action(type).equals(value.get("action"))) result.put("action",action(type));
        var actor = actorId(type,value);
        result.put("bandIdentityVersion",actor.isPresent() ? 1 : 0);
        actor.ifPresent(id -> result.put(actorKey(type),id.toString()));
        return Collections.unmodifiableMap(result);
    }
    public static String title(NotificationType type) {
        return switch (type) {
            case BAND_INVITE_RECEIVED -> "Yeni grup daveti";
            case BAND_INVITE_ACCEPTED -> "Grup daveti kabul edildi";
            case BAND_INVITE_REJECTED -> "Grup daveti reddedildi";
            case BAND_MEMBER_REMOVED -> "Grup üyeliğin sonlandırıldı";
            case BAND_MEMBER_LEFT -> "Bir üye gruptan ayrıldı";
            default -> throw new IllegalArgumentException("Not a BAND identity notification");
        };
    }
}
