package com.berkayb.soundconnect.modules.notification.service;

import com.berkayb.soundconnect.modules.notification.dto.response.TableNotificationTargetResponse;
import com.berkayb.soundconnect.modules.notification.dto.response.TableNotificationTargetResponse.Kind;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TableNotificationTargetService {
    private final NamedParameterJdbcTemplate jdbc;

    // Single MVCC snapshot: owned notification + durable source proof + current
    // aggregate/membership/account authority. A payload alone never proves an event.
    private static final String TARGET = """
        with owned as (
          select n.*,e.occurred_at as source_occurred_at,
            case when n.type='TABLE_JOIN_REQUEST_RECEIVED' then n.payload->>'applicantId'
                 when n.type='TABLE_PARTICIPANT_LEFT' then n.payload->>'leaverId'
                 else n.recipient_id::text end as subject
          from tbl_notification n
          join tbl_notification_receipt r on r.source_event_id=n.source_event_id and r.recipient_id=n.recipient_id
          join tbl_table_notification_event e on e.event_id=n.source_event_id
            and e.recipient_id=n.recipient_id and e.notification_type=n.type and e.payload=n.payload
          where n.id=:notification and n.recipient_id=:recipient
            and n.payload->>'module'='TABLE'
        )
        select n.id,n.type,n.is_read,n.source_occurred_at,t.id as table_id,t.description,t.status as table_status,
          n.payload->>'action' as action,n.payload->>'reason' as reason,s.id as subject_id,
          n.payload->>'applicationId' as application_id,p.status as participant_status,
          coalesce(p.application_id::text=n.payload->>'applicationId',false) as same_application,
          t.status='ACTIVE' and t.expires_at>current_timestamp as usable
        from owned n
        join tbl_table_group t on t.id::text=n.payload->>'tableGroupId'
        join tbl_user u on u.id=n.recipient_id
        join tbl_user o on o.id=t.owner_id
        join tbl_user s on s.id::text=n.subject and s.id<>t.owner_id
        left join tbl_table_group_participants p on p.table_group_id=t.id and p.user_id=s.id
        where u.status='ACTIVE' and u.email_verified and u.erased_at is null
          and o.status='ACTIVE' and o.email_verified and o.erased_at is null
          and s.status='ACTIVE' and s.email_verified and s.erased_at is null
          and not exists(select 1 from user_roles ur join tbl_role role on role.id=ur.role_id
              where ur.user_id in (u.id,o.id,s.id) and role.name in ('ROLE_VENUE','ROLE_STUDIO'))
          and not exists(select 1 from tbl_studio_profile sp where sp.user_id in (u.id,o.id,s.id))
          and not exists(select 1 from tbl_venues v where v.owner_id in (u.id,o.id,s.id))
          and (not jsonb_exists(n.payload,'applicationId') or n.payload->>'applicationId' ~
            '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')
          and case n.type
            when 'TABLE_JOIN_REQUEST_RECEIVED' then n.recipient_id=t.owner_id
              and n.payload->>'action'='JOIN_REQUEST_RECEIVED'
            when 'TABLE_PARTICIPANT_LEFT' then n.recipient_id=t.owner_id
              and n.payload->>'action'='PARTICIPANT_LEFT'
            when 'TABLE_JOIN_REQUEST_APPROVED' then n.recipient_id=s.id
              and n.payload->>'ownerId'=t.owner_id::text and n.payload->>'action'='JOIN_REQUEST_APPROVED'
            when 'TABLE_JOIN_REQUEST_REJECTED' then n.recipient_id=s.id
              and n.payload->>'ownerId'=t.owner_id::text and n.payload->>'action'='JOIN_REQUEST_REJECTED'
            when 'TABLE_REMOVED' then n.recipient_id=s.id
              and n.payload->>'ownerId'=t.owner_id::text and n.payload->>'action'='PARTICIPANT_REMOVED'
            when 'TABLE_CANCELLED' then n.recipient_id=s.id and p.status='ACCEPTED' and t.status='CANCELLED'
              and n.payload->>'action'='CANCELLED'
              and n.payload->>'reason' in ('OWNER_CANCELLED','OWNER_JOINED_ANOTHER_TABLE')
            when 'TABLE_EXPIRED' then n.recipient_id=s.id and p.status='ACCEPTED' and t.status='INACTIVE'
              and n.payload->>'ownerId'=t.owner_id::text and n.payload->>'action'='EXPIRED'
            else false end
        """;

    @Transactional(readOnly = true)
    public TableNotificationTargetResponse resolve(UUID recipient, UUID notification) {
        if (recipient == null) throw new SoundConnectException(ErrorType.UNAUTHORIZED);
        if (notification == null) throw unavailable();
        var rows = jdbc.query(TARGET, Map.of("recipient", recipient, "notification", notification), (rs, index) -> {
            var type = NotificationType.valueOf(rs.getString("type"));
            boolean same = rs.getBoolean("same_application"), usable = rs.getBoolean("usable");
            String status = rs.getString("participant_status"), application = rs.getString("application_id");
            Kind kind = same && usable && type == NotificationType.TABLE_JOIN_REQUEST_RECEIVED && "PENDING".equals(status)
                    ? Kind.PENDING_APPLICATION
                    : same && usable && type == NotificationType.TABLE_JOIN_REQUEST_APPROVED && "ACCEPTED".equals(status)
                    ? Kind.CHAT : Kind.RESULT;
            return new TableNotificationTargetResponse(notification,recipient,type,rs.getObject("table_id",UUID.class),
                    kind,rs.getString("action"),rs.getTimestamp("source_occurred_at").toInstant(),
                    rs.getString("description"),rs.getString("table_status"),status==null?"NOT_PRESENT":status,
                    rs.getObject("subject_id",UUID.class),application==null?null:UUID.fromString(application),
                    same,rs.getString("reason"),rs.getBoolean("is_read"));
        });
        if (rows.size()!=1) throw unavailable();
        return rows.getFirst();
    }

    private static SoundConnectException unavailable() {
        return new SoundConnectException(ErrorType.NOTIFICATION_NOT_FOUND);
    }
}
