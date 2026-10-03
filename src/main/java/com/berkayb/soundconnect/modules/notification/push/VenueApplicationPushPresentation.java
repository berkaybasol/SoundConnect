package com.berkayb.soundconnect.modules.notification.push;

import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import java.util.Set;

public final class VenueApplicationPushPresentation {
    private VenueApplicationPushPresentation() { }
    public static final String CAPABILITY = "ANDROID_NATIVE_V3";
    public static final String VERSION = "ANDROID_VENUE_APPLICATION_V1";
    public static boolean supportsApplication(String capability) {
        return CAPABILITY.equals(capability) || StudioPushPresentation.supportsStudio(capability);
    }
    public static final Set<NotificationType> TYPES = Set.of(
            NotificationType.VENUE_APPLICATION_APPROVED, NotificationType.VENUE_APPLICATION_REJECTED);
}
