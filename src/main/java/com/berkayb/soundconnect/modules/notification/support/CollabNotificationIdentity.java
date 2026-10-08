package com.berkayb.soundconnect.modules.notification.support;

import com.berkayb.soundconnect.modules.notification.dto.response.NotificationResponseDto;
import com.berkayb.soundconnect.modules.notification.entity.Notification;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import java.time.Instant;
import java.util.*;

/** Exact occurrence and current authority, shared by native plan/send and inbox.
 * Outbox retention is deliberately not the lifetime of an owned inbox target. */
public final class CollabNotificationIdentity {
    public static final Set<NotificationType> TYPES = Set.of(
        NotificationType.COLLAB_APPLICATION_RECEIVED, NotificationType.COLLAB_APPLICATION_ACCEPTED,
        NotificationType.COLLAB_APPLICATION_REJECTED, NotificationType.COLLAB_APPLICATION_WITHDRAWN,
        NotificationType.COLLAB_APPLICATION_INVALIDATED, NotificationType.COLLAB_LISTING_EXPIRED,
        NotificationType.COLLAB_JOB_COMPLETION_REQUESTED, NotificationType.COLLAB_JOB_COMPLETED,
        NotificationType.COLLAB_REVIEW_RECEIVED, NotificationType.COLLAB_LISTING_REMOVED,
        NotificationType.COLLAB_REPORT_RESOLVED);
    private static final ObjectMapper JSON = new ObjectMapper();
    private CollabNotificationIdentity() { }

    // Same current scalar business account rules as CollabAccessGuard, including
    // actual profile aggregates (not just role rows or a captured JWT).
    private static final String TARGET = """
        with profiles as (
          select user_id,'ROLE_MUSICIAN' role from tbl_musician_profile
          union all select user_id,'ROLE_STUDIO' from tbl_studio_profile
          union all select owner_id,'ROLE_VENUE' from tbl_venues
          union all select user_id,'ROLE_LISTENER' from "tbl_listener-profile"
          union all select user_id,'ROLE_ORGANIZER' from tbl_organizer_profile
          union all select user_id,'ROLE_PRODUCER' from tbl_producer_profile
        ), audience as (
          select u.id from tbl_user u where u.status='ACTIVE' and u.email_verified and u.erased_at is null
            and (select count(distinct role) from profiles p where p.user_id=u.id)=1
            and (select count(*) from user_roles ur join tbl_role r on r.id=ur.role_id where ur.user_id=u.id
              and r.name in ('ROLE_MUSICIAN','ROLE_VENUE','ROLE_STUDIO','ROLE_LISTENER','ROLE_ORGANIZER','ROLE_PRODUCER'))=1
            and exists(select 1 from profiles p join user_roles ur on ur.user_id=p.user_id
              join tbl_role r on r.id=ur.role_id and r.name=p.role
              where p.user_id=u.id and p.role in ('ROLE_MUSICIAN','ROLE_VENUE','ROLE_STUDIO'))
        ), actor_owners as (
          select a.id,p.user_id from tbl_collab_actor a join tbl_musician_profile p on p.id=a.source_profile_id
            where a.active and a.profile_type='MUSICIAN'
          union all select a.id,p.user_id from tbl_collab_actor a join tbl_studio_profile p on p.id=a.source_profile_id
            where a.active and a.profile_type='STUDIO'
          union all select a.id,p.owner_id from tbl_collab_actor a join tbl_venues p on p.id=a.source_profile_id
            where a.active and a.profile_type='VENUE'
          union all select a.id,p.user_id from tbl_collab_actor a join tbl_band_member p on p.band_id=a.source_profile_id
            where a.active and a.profile_type='BAND' and p.status='ACTIVE' and p.band_role='FOUNDER'
        )
        select n.id,n.recipient_id,n.source_event_id,n.type,n.is_read,n.occurred_at,n.payload::text,
          o.event_id is not null as has_source
        from tbl_notification n
        join tbl_notification_receipt receipt on receipt.source_event_id=n.source_event_id and receipt.recipient_id=n.recipient_id
        join audience recipient on recipient.id=n.recipient_id
        join tbl_collab l on l.id::text=n.payload->>'listingId'
        join audience owner on owner.id=l.owner_user_id
        join actor_owners publisher on publisher.id=l.publisher_actor_id and publisher.user_id=l.owner_user_id
        left join tbl_collab_application a on a.id::text=n.payload->>'applicationId' and a.collab_id=l.id
        left join tbl_collab_job j on j.id::text=n.payload->>'jobId' and j.collab_id=l.id
          and j.publisher_user_id=l.owner_user_id and j.publisher_actor_id=l.publisher_actor_id
        left join tbl_collab_application ja on ja.id=j.application_id and ja.collab_id=l.id
          and ja.applicant_user_id=j.applicant_user_id and ja.applicant_actor_id=j.applicant_actor_id
          and ja.status='ACCEPTED'
        left join tbl_collab_review v on v.id::text=n.payload->>'reviewId' and v.job_id=j.id
        left join tbl_collab_report r on r.id::text=n.payload->>'reportId' and r.collab_id=l.id
        left join tbl_collab_notification_outbox o on o.event_id=n.source_event_id
        where n.id=:notification and n.recipient_id=:recipient
          and n.occurred_at is not null and n.payload->>'module'='COLLAB'
          and n.payload->>'action'=substring(n.type from 8)
          and (not :native or (not n.is_read and o.event_id is not null))
          and (o.event_id is null or (o.recipient_id=n.recipient_id and o.notification_type=n.type
            and o.occurred_at=n.occurred_at and o.payload=n.payload))
          and (a.id is null or (exists(select 1 from audience u where u.id=a.applicant_user_id)
            and exists(select 1 from actor_owners ao where ao.id=a.applicant_actor_id and ao.user_id=a.applicant_user_id)))
          and (j.id is null or (ja.id is not null and exists(select 1 from audience u where u.id=j.applicant_user_id)
            and exists(select 1 from actor_owners ao where ao.id=j.applicant_actor_id and ao.user_id=j.applicant_user_id)))
          and case n.type
            when 'COLLAB_APPLICATION_RECEIVED' then a.id is not null and n.recipient_id=l.owner_user_id
              and a.submitted_at=n.occurred_at
            when 'COLLAB_APPLICATION_WITHDRAWN' then a.id is not null and n.recipient_id=l.owner_user_id
              and a.status='WITHDRAWN_BY_APPLICANT' and a.status_changed_at=n.occurred_at
            when 'COLLAB_APPLICATION_ACCEPTED' then a.id is not null and n.recipient_id=a.applicant_user_id
              and a.status='ACCEPTED' and a.decided_at=n.occurred_at and j.application_id=a.id
            when 'COLLAB_APPLICATION_REJECTED' then a.id is not null and n.recipient_id=a.applicant_user_id
              and a.status='REJECTED' and a.decided_at=n.occurred_at
            when 'COLLAB_APPLICATION_INVALIDATED' then a.id is not null and n.recipient_id=a.applicant_user_id
              and a.status='INVALIDATED_BY_LISTING_CLOSURE' and a.status_changed_at=n.occurred_at
            when 'COLLAB_LISTING_EXPIRED' then n.recipient_id=l.owner_user_id and l.status in ('EXPIRED','CLOSED')
              and l.expires_at<=n.occurred_at and l.closed_at>=n.occurred_at
            when 'COLLAB_LISTING_REMOVED' then n.recipient_id=l.owner_user_id and l.status='CLOSED'
              and l.closure_reason='ADMIN_REMOVED' and l.closed_at=n.occurred_at
            when 'COLLAB_JOB_COMPLETION_REQUESTED' then j.status in ('ACTIVE','COMPLETED') and
              ((n.recipient_id=j.publisher_user_id and j.applicant_confirmed_at=n.occurred_at)
                or (n.recipient_id=j.applicant_user_id and j.publisher_confirmed_at=n.occurred_at))
            when 'COLLAB_JOB_COMPLETED' then j.status='COMPLETED' and j.completed_at=n.occurred_at
              and n.recipient_id in (j.publisher_user_id,j.applicant_user_id)
            when 'COLLAB_REVIEW_RECEIVED' then j.status='COMPLETED' and v.submitted_at=n.occurred_at
              and ((n.recipient_id=j.publisher_user_id and v.target_actor_id=j.publisher_actor_id
                    and v.reviewer_actor_id=j.applicant_actor_id and v.reviewer_user_id=j.applicant_user_id)
                or (n.recipient_id=j.applicant_user_id and v.target_actor_id=j.applicant_actor_id
                    and v.reviewer_actor_id=j.publisher_actor_id and v.reviewer_user_id=j.publisher_user_id))
            when 'COLLAB_REPORT_RESOLVED' then r.reporter_user_id=n.recipient_id and r.reviewed_at<=n.occurred_at
              and r.review_decision=n.payload->>'decision'
              and ((r.status='ACTIONED' and r.review_decision='REMOVE_LISTING')
                or (r.status='DISMISSED' and r.review_decision='DISMISS'))
            else false end
        """;

    public record Owned(NotificationResponseDto notification, UUID sourceEventId, Instant occurredAt, boolean hasSource) { }

    public static Optional<Owned> resolve(NamedParameterJdbcTemplate jdbc, UUID recipient, UUID notification, boolean nativePush) {
        if (recipient==null || notification==null) return Optional.empty();
        var rows=jdbc.query(TARGET,Map.of("recipient",recipient,"notification",notification,"native",nativePush),(rs,index)-> {
            Map<String,Object> payload;
            try { payload=JSON.readValue(rs.getString("payload"),new TypeReference<>(){}); }
            catch (java.io.IOException malformed) { return null; }
            var type=NotificationType.valueOf(rs.getString("type"));
            if (!validPayload(type,payload)) return null;
            var time=rs.getTimestamp("occurred_at").toInstant();
            return new Owned(new NotificationResponseDto(notification,recipient,type,"İş birliği bildirimi","",
                rs.getBoolean("is_read"),time,Map.copyOf(payload)),rs.getObject("source_event_id",UUID.class),time,rs.getBoolean("has_source"));
        });
        return rows.size()==1 ? Optional.ofNullable(rows.getFirst()) : Optional.empty();
    }

    public static boolean matches(Owned owned, Notification n) {
        return n!=null && !n.isRead() && owned.notification().id().equals(n.getId())
            && owned.notification().recipientId().equals(n.getRecipientId()) && owned.notification().type()==n.getType()
            && owned.sourceEventId().equals(n.getSourceEventId())
            && n.getOccurredAt()!=null && owned.occurredAt().isAfter(n.getOccurredAt().minusNanos(1000))
            && owned.occurredAt().isBefore(n.getOccurredAt().plusNanos(1000))
            && JSON.valueToTree(owned.notification().payload()).equals(JSON.valueToTree(n.getPayload()));
    }

    private static boolean validPayload(NotificationType type,Map<String,Object> p) {
        if (!TYPES.contains(type)) return false;
        var keys=new HashSet<>(Set.of("module","action","listingId"));
        if (type.name().startsWith("COLLAB_APPLICATION_")) keys.add("applicationId");
        if (type==NotificationType.COLLAB_APPLICATION_ACCEPTED || type.name().startsWith("COLLAB_JOB_")
            || type==NotificationType.COLLAB_REVIEW_RECEIVED) keys.add("jobId");
        if (type==NotificationType.COLLAB_REVIEW_RECEIVED) keys.add("reviewId");
        if (type==NotificationType.COLLAB_REPORT_RESOLVED) keys.addAll(Set.of("reportId","decision"));
        if (!p.keySet().equals(keys)) return false;
        for (String key:keys) if (key.endsWith("Id")) {
            Object value=p.get(key);
            if (!(value instanceof String text)) return false;
            try { if (!UUID.fromString(text).toString().equals(text)) return false; }
            catch (IllegalArgumentException malformed) { return false; }
        }
        return true;
    }
}
