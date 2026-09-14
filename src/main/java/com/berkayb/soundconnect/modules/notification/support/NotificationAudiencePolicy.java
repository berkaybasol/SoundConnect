package com.berkayb.soundconnect.modules.notification.support;

import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import java.util.Arrays;
import java.util.List;

/** The inbox and delivery workers share an explicit product boundary, independent of JWT snapshots. */
public final class NotificationAudiencePolicy {
    private NotificationAudiencePolicy() { }

    // Alias n belongs to tbl_notification. Apply before ordering, limits and counts.
    public static final String VISIBLE_SQL = """
            (n.type not in (:businessTypes) or not exists (
                select 1 from user_roles membership join tbl_role role on role.id=membership.role_id
                where membership.user_id=:recipient and role.name='ROLE_LISTENER'))
            """;
    public static final String LISTENER_SQL = """
            select exists(select 1 from user_roles membership join tbl_role role on role.id=membership.role_id
                where membership.user_id=:recipient and role.name='ROLE_LISTENER')
            """;

    public static boolean businessOnly(NotificationType type) {
        if (type == null) return false;
        // Name individual event types: a future public event reminder must not
        // accidentally inherit the performer/venue workflow restriction.
        return switch (type) {
            case STUDIO_RESERVATION_CREATED, STUDIO_RESERVATION_CONFLICTING_REQUESTS,
                    STUDIO_RESERVATION_APPROVED, STUDIO_RESERVATION_REJECTED,
                    STUDIO_RESERVATION_CANCELLED_BY_CUSTOMER, STUDIO_RESERVATION_CANCELLED_BY_STUDIO,
                    VENUE_APPLICATION_REJECTED,
                    ARTIST_VENUE_LINK_APPLICATION_REQUEST, ARTIST_VENUE_LINK_APPLICATION_ACCEPT,
                    ARTIST_VENUE_LINK_APPLICATION_REJECT,
                    EVENT_PERFORMER_ADDED, EVENT_PERFORMER_APPROVAL_REQUESTED, EVENT_PERFORMER_APPROVED,
                    EVENT_PERFORMER_REJECTED, EVENT_VENUE_APPROVAL_REQUESTED, EVENT_VENUE_APPROVED,
                    EVENT_VENUE_REJECTED,
                    BAND_INVITE_RECEIVED, BAND_INVITE_ACCEPTED, BAND_INVITE_REJECTED,
                    BAND_MEMBER_REMOVED, BAND_MEMBER_LEFT,
                    COLLAB_APPLICATION_RECEIVED, COLLAB_APPLICATION_ACCEPTED, COLLAB_APPLICATION_REJECTED,
                    COLLAB_APPLICATION_WITHDRAWN, COLLAB_APPLICATION_INVALIDATED, COLLAB_LISTING_EXPIRED,
                    COLLAB_JOB_COMPLETION_REQUESTED, COLLAB_JOB_COMPLETED, COLLAB_REVIEW_RECEIVED,
                    COLLAB_LISTING_REMOVED, COLLAB_REPORT_RESOLVED -> true;
            default -> false;
        };
    }

    public static final List<String> BUSINESS_TYPE_NAMES = Arrays.stream(NotificationType.values())
            .filter(NotificationAudiencePolicy::businessOnly).map(Enum::name).toList();
}
