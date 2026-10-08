package com.berkayb.soundconnect.modules.notification.push;

import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import java.util.Set;

/** Closed Android studio contract; never contains a reservation snapshot. */
public final class StudioPushPresentation {
    public static final String CAPABILITY = "ANDROID_NATIVE_V4";
    public static final String VERSION = "ANDROID_STUDIO_V1";
    public static final Set<NotificationType> TYPES = Set.of(
            NotificationType.STUDIO_RESERVATION_CREATED, NotificationType.STUDIO_RESERVATION_CONFLICTING_REQUESTS,
            NotificationType.STUDIO_RESERVATION_APPROVED, NotificationType.STUDIO_RESERVATION_REJECTED,
            NotificationType.STUDIO_RESERVATION_CANCELLED_BY_CUSTOMER, NotificationType.STUDIO_RESERVATION_CANCELLED_BY_STUDIO);
    public enum Variant {
        STUDIO_CREATED_PENDING, STUDIO_CREATED_CONFIRMED, STUDIO_CONFLICTING_REQUESTS, STUDIO_APPROVED,
        STUDIO_REJECTED, STUDIO_REJECTED_CONFLICT, STUDIO_CANCELLED_BY_CUSTOMER,
        STUDIO_CANCELLED_BY_STUDIO, STUDIO_ROOM_ARCHIVED
    }
    private StudioPushPresentation() { }
    public static boolean supportsStudio(String capability) { return CAPABILITY.equals(capability) || FollowPushPresentation.supportsFollow(capability); }
    public static boolean valid(String type, String variant) {
        try {
            var n = NotificationType.valueOf(type); var v = Variant.valueOf(variant);
            return switch (n) {
                case STUDIO_RESERVATION_CREATED -> v == Variant.STUDIO_CREATED_PENDING || v == Variant.STUDIO_CREATED_CONFIRMED;
                case STUDIO_RESERVATION_CONFLICTING_REQUESTS -> v == Variant.STUDIO_CONFLICTING_REQUESTS;
                case STUDIO_RESERVATION_APPROVED -> v == Variant.STUDIO_APPROVED;
                case STUDIO_RESERVATION_REJECTED -> v == Variant.STUDIO_REJECTED || v == Variant.STUDIO_REJECTED_CONFLICT;
                case STUDIO_RESERVATION_CANCELLED_BY_CUSTOMER -> v == Variant.STUDIO_CANCELLED_BY_CUSTOMER;
                case STUDIO_RESERVATION_CANCELLED_BY_STUDIO -> v == Variant.STUDIO_CANCELLED_BY_STUDIO || v == Variant.STUDIO_ROOM_ARCHIVED;
                default -> false;
            };
        } catch (IllegalArgumentException | NullPointerException invalid) { return false; }
    }
}