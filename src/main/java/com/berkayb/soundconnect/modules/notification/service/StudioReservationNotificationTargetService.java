package com.berkayb.soundconnect.modules.notification.service;

import com.berkayb.soundconnect.modules.notification.dto.response.StudioReservationNotificationTargetResponse;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.studio.reservation.enums.StudioReservationStatus;
import com.berkayb.soundconnect.modules.studio.reservation.support.StudioReservationTimeProvider;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class StudioReservationNotificationTargetService {
    private final NamedParameterJdbcTemplate jdbc;
    private final StudioReservationTimeProvider time;

    // One statement observes the owned notification, source chain and current account
    // together. Payload IDs select a source; they never confer authority. Unlike push
    // eligibility, this history read permits later state changes, past dates and archives.
    private static final String TARGET = """
            select n.id as notification_id,n.recipient_id,n.type,r.id as reservation_id,
                   r.room_id,rm.studio_profile_id,coalesce(nullif(btrim(sp.name),''),'Stüdyo') as studio_name,
                   rm.name as room_name,sp.user_id=:recipient as owner_mode,r.status,
                   rm.archived_at is not null as room_archived,r.starts_at,r.ends_at,sp.time_zone
            from tbl_notification n
            join tbl_user u on u.id=n.recipient_id
            join tbl_studio_room_reservation r on r.id=case
                 when n.payload->>'reservationId' ~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
                 then (n.payload->>'reservationId')::uuid else null end
            join tbl_studio_room rm on rm.id=r.room_id and rm.id::text=n.payload->>'roomId'
            join tbl_studio_profile sp on sp.id=rm.studio_profile_id
                 and sp.id::text=n.payload->>'studioProfileId'
            where n.id=:notification and n.recipient_id=:recipient
              and n.payload->>'module'='STUDIO' and r.requester_id::text=n.payload->>'requesterId'
              and u.status='ACTIVE' and u.email_verified and u.erased_at is null
              and exists(select 1 from user_roles ur join tbl_role role on role.id=ur.role_id
                         where ur.user_id=u.id and role.name<>'ROLE_LISTENER')
              and not exists(select 1 from user_roles ur join tbl_role role on role.id=ur.role_id
                             where ur.user_id=u.id and role.name='ROLE_LISTENER')
              and not exists(select 1 from "tbl_listener-profile" lp where lp.user_id=u.id)
              and r.requester_id<>sp.user_id
              and (
                (n.type in ('STUDIO_RESERVATION_CREATED','STUDIO_RESERVATION_CONFLICTING_REQUESTS',
                            'STUDIO_RESERVATION_CANCELLED_BY_CUSTOMER')
                 and sp.user_id=:recipient
                 and exists(select 1 from user_roles ur join tbl_role role on role.id=ur.role_id
                            where ur.user_id=u.id and role.name='ROLE_STUDIO'))
                or (n.type in ('STUDIO_RESERVATION_APPROVED','STUDIO_RESERVATION_REJECTED',
                               'STUDIO_RESERVATION_CANCELLED_BY_STUDIO') and r.requester_id=:recipient)
              )
              and case n.type
                when 'STUDIO_RESERVATION_CREATED' then n.payload->>'action'='CREATED'
                when 'STUDIO_RESERVATION_CONFLICTING_REQUESTS' then n.payload->>'action'='CONFLICTING_REQUESTS'
                when 'STUDIO_RESERVATION_APPROVED' then n.payload->>'action'='APPROVED'
                when 'STUDIO_RESERVATION_REJECTED' then n.payload->>'action' in ('REJECTED','AUTO_REJECTED_CONFLICT')
                when 'STUDIO_RESERVATION_CANCELLED_BY_CUSTOMER' then n.payload->>'action'='CANCELLED_BY_CUSTOMER'
                when 'STUDIO_RESERVATION_CANCELLED_BY_STUDIO' then n.payload->>'action' in
                     ('CANCELLED_BY_STUDIO','CANCELLED_BY_STUDIO_ROOM_ARCHIVED')
                else false end
            """;

    @Transactional(readOnly = true)
    public StudioReservationNotificationTargetResponse resolve(UUID recipient, UUID notificationId) {
        if (recipient == null) throw new SoundConnectException(ErrorType.UNAUTHORIZED);
        if (notificationId == null) throw unavailable();
        Instant now = time.now();
        var rows = jdbc.query(TARGET, Map.of("recipient", recipient, "notification", notificationId), (rs, row) -> {
            Instant start = rs.getTimestamp("starts_at").toInstant(), end = rs.getTimestamp("ends_at").toInstant();
            ZoneId zone = zone(rs.getString("time_zone"));
            var localStart = start.atZone(zone); var localEnd = end.atZone(zone);
            var status = StudioReservationStatus.valueOf(rs.getString("status"));
            if (status == StudioReservationStatus.PENDING_APPROVAL && !start.isAfter(now))
                status = StudioReservationStatus.EXPIRED;
            return new StudioReservationNotificationTargetResponse(
                    rs.getObject("notification_id", UUID.class), recipient,
                    NotificationType.valueOf(rs.getString("type")),rs.getObject("reservation_id",UUID.class),
                    rs.getObject("room_id",UUID.class),rs.getObject("studio_profile_id",UUID.class),
                    rs.getString("studio_name"),rs.getString("room_name"),rs.getBoolean("owner_mode"),
                    status,rs.getBoolean("room_archived"),status==StudioReservationStatus.CONFIRMED && !end.isAfter(now),
                    start,end,zone.getId(),localStart.toLocalDate(),localEnd.toLocalDate(),
                    localStart.toLocalTime(),localEnd.toLocalTime());
        });
        if (rows.size()!=1) throw unavailable();
        return rows.getFirst();
    }

    private static ZoneId zone(String value) {
        try { if (value!=null && !value.isBlank()) return ZoneId.of(value); }
        catch (DateTimeException ignored) { }
        // Match the existing booking service's fallback for legacy configuration.
        return ZoneId.of("Europe/Istanbul");
    }
    private static SoundConnectException unavailable() {
        return new SoundConnectException(ErrorType.STUDIO_RESERVATION_NOT_FOUND);
    }
}
