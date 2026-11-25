package com.berkayb.soundconnect.modules.notification.support;

import com.berkayb.soundconnect.modules.notification.service.NotificationDeliveryPolicy;
import com.berkayb.soundconnect.modules.user.support.AccountDeliveryFence;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationInboundEvent;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The application's or reservation's persisted participants also identify legacy queued requests. */
public final class NewRequestNotificationContact {
    private NewRequestNotificationContact() {}

    public static boolean eligible(NamedParameterJdbcTemplate jdbc, AccountDeliveryFence accounts,
                                   NotificationInboundEvent event) {
        String sql;
        UUID source;
        UUID parent;
        switch (event.type()) {
            case COLLAB_APPLICATION_RECEIVED -> {
                source = NotificationDeliveryPolicy.uuid(event.payload(), "applicationId");
                parent = NotificationDeliveryPolicy.uuid(event.payload(), "listingId");
                sql = """
                        select a.applicant_user_id from tbl_collab_application a
                        join tbl_collab c on c.id=a.collab_id
                        where a.id=:source and c.id=:parent and c.owner_user_id=:recipient
                        """;
            }
            case COLLAB_REVIEW_RECEIVED -> {
                source = NotificationDeliveryPolicy.uuid(event.payload(), "reviewId");
                parent = NotificationDeliveryPolicy.uuid(event.payload(), "jobId");
                sql = """
                        select r.reviewer_user_id from tbl_collab_review r
                        join tbl_collab_job j on j.id=r.job_id
                        where r.id=:source and j.id=:parent
                          and ((j.publisher_user_id=:recipient and j.applicant_user_id=r.reviewer_user_id)
                            or (j.applicant_user_id=:recipient and j.publisher_user_id=r.reviewer_user_id))
                        """;
            }
            case STUDIO_RESERVATION_CREATED, STUDIO_RESERVATION_CONFLICTING_REQUESTS -> {
                source = NotificationDeliveryPolicy.uuid(event.payload(), "reservationId");
                parent = NotificationDeliveryPolicy.uuid(event.payload(), "roomId");
                sql = """
                        select r.requester_id from tbl_studio_room_reservation r
                        join tbl_studio_room room on room.id=r.room_id
                        join tbl_studio_profile studio on studio.id=room.studio_profile_id
                        where r.id=:source and room.id=:parent and studio.user_id=:recipient
                        """;
            }
            default -> { return true; }
        }
        if (source == null || parent == null) return false;
        List<UUID> senders = jdbc.queryForList(sql,
                Map.of("source", source, "parent", parent, "recipient", event.recipientId()), UUID.class);
        return senders.size() == 1 && !accounts.contactEventSuppressed(
                senders.getFirst(), event.recipientId(), event.occurredAt());
    }
}
