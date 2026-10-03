package com.berkayb.soundconnect.modules.application.venueapplication.service;

import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.notification.push.VenueApplicationPushPresentation;
import com.berkayb.soundconnect.modules.notification.service.NotificationDeliveryPolicy;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationInboundEvent;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import java.util.Map;
import java.util.UUID;

/** Fresh final-decision ownership/account/source fence, shared by admission and dispatch. */
public final class VenueApplicationDecisionEligibility {
    private final NamedParameterJdbcTemplate jdbc;
    public VenueApplicationDecisionEligibility(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public boolean eligible(NotificationInboundEvent event) {
        if (event == null || !VenueApplicationPushPresentation.TYPES.contains(event.type()) || event.payload() == null) return false;
        var p = event.payload(); UUID id = NotificationDeliveryPolicy.uuid(p,"applicationId");
        if (id == null || !event.recipientId().equals(NotificationDeliveryPolicy.uuid(p,"applicantUserId"))
                || !"VENUE_APPLICATION".equals(p.get("module"))) return false;
        String state = event.type() == NotificationType.VENUE_APPLICATION_APPROVED ? "APPROVED" : "REJECTED";
        if (!state.equals(p.get("status")) || !("APPLICATION_" + state).equals(p.get("action"))) return false;
        // NOWAIT avoids inversion with decision writers (application -> account). Busy source
        // transactions use the existing durable retry. No lock is held across provider I/O.
        var source = jdbc.query("""
                select a.status,a.approved_venue_id from tbl_venue_applications a
                where a.id=:id and a.user_id=:user and a.decision_date is not null
                  and a.id=(select latest.id from tbl_venue_applications latest where latest.user_id=:user
                    order by latest.application_date desc,latest.created_at desc,latest.id desc limit 1)
                for share of a nowait
                """, Map.of("id",id,"user",event.recipientId()), (rs,index) ->
                Map.entry(rs.getString("status"), java.util.Optional.ofNullable(rs.getObject("approved_venue_id",UUID.class))));
        if (source.isEmpty() || !state.equals(source.getFirst().getKey())) return false;
        boolean approved = "APPROVED".equals(state);
        Boolean account = jdbc.queryForObject("""
                select exists(select 1 from tbl_user u where u.id=:user and u.erased_at is null and u.email_verified
                  and ((:approved and u.status='ACTIVE' and exists(select 1 from user_roles ur join tbl_role r
                       on r.id=ur.role_id where ur.user_id=u.id and r.name='ROLE_VENUE'))
                    or (not :approved and u.status='PENDING_VENUE_REQUEST' and not exists(
                       select 1 from user_roles ur where ur.user_id=u.id) and not exists (select 1 from user_permissions up where up.user_id=u.id))))
                """, Map.of("user",event.recipientId(),"approved",approved), Boolean.class);
        if (!Boolean.TRUE.equals(account)) return false;
        if (!approved) return source.getFirst().getValue().isEmpty();
        var venue = source.getFirst().getValue();
        return venue.isPresent() && !jdbc.query("""
                select id from tbl_venues where id=:venue and owner_id=:user and status='APPROVED' for share nowait
                """, Map.of("venue",venue.get(),"user",event.recipientId()), (rs,index) -> rs.getObject(1,UUID.class)).isEmpty();
    }
}
