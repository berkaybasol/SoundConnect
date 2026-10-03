package com.berkayb.soundconnect.modules.notification.push;

import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import java.util.EnumSet;
import java.util.Set;

/** Closed wire contract; a device capability is not itself a payload version. */
public final class VenuePushPresentation {
    public static final String CAPABILITY = "ANDROID_NATIVE_V2";
    public static final String VERSION = "ANDROID_VENUE_V1";
    public static final Set<NotificationType> TYPES = Set.copyOf(EnumSet.of(
            NotificationType.ARTIST_VENUE_LINK_APPLICATION_REQUEST,
            NotificationType.ARTIST_VENUE_LINK_APPLICATION_ACCEPT,
            NotificationType.ARTIST_VENUE_LINK_APPLICATION_REJECT,
            NotificationType.EVENT_PERFORMER_APPROVAL_REQUESTED,
            NotificationType.EVENT_PERFORMER_APPROVED,
            NotificationType.EVENT_PERFORMER_REJECTED));
    public enum Variant { DEFAULT, PROFILE_VISIBILITY, PLAN_CONSENT, PLAN_WITHDRAWN }
    private VenuePushPresentation() { }
    public static boolean supportsDm(String capability) {
        return DmPushPresentation.VERSION.equals(capability) || supportsVenue(capability);
    }
    public static boolean supportsVenue(String capability) {
        return CAPABILITY.equals(capability) || VenueApplicationPushPresentation.supportsApplication(capability);
    }
    public static boolean connection(NotificationType type) {
        return type == NotificationType.ARTIST_VENUE_LINK_APPLICATION_REQUEST
                || type == NotificationType.ARTIST_VENUE_LINK_APPLICATION_ACCEPT
                || type == NotificationType.ARTIST_VENUE_LINK_APPLICATION_REJECT;
    }
    public static boolean valid(String type, String variant) {
        try {
            var parsed = NotificationType.valueOf(type);
            var display = Variant.valueOf(variant);
            return TYPES.contains(parsed) && (connection(parsed) ? display == Variant.DEFAULT
                    : display != Variant.PLAN_WITHDRAWN || parsed == NotificationType.EVENT_PERFORMER_REJECTED);
        } catch (IllegalArgumentException | NullPointerException invalid) { return false; }
    }
}
