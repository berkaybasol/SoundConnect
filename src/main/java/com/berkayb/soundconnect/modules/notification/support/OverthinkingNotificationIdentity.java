package com.berkayb.soundconnect.modules.notification.support;

import com.berkayb.soundconnect.modules.notification.dto.response.NotificationResponseDto;
import com.berkayb.soundconnect.modules.notification.entity.Notification;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.notification.service.NotificationDeliveryPolicy;
import com.berkayb.soundconnect.modules.overthinking.support.OverthinkingMainstageVisibility;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationInboundEvent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** One durable reveal occurrence. Native delivery requires a live source; an
 * owned receipted historical inbox row survives normal outbox retention. */
public final class OverthinkingNotificationIdentity {
    public static final Set<NotificationType> TYPES = Set.of(
        NotificationType.OVERTHINKING_REVEAL_REQUEST_RECEIVED,
        NotificationType.OVERTHINKING_REVEAL_REQUEST_APPROVED,
        NotificationType.OVERTHINKING_REVEAL_REQUEST_REJECTED);
    private static final ObjectMapper JSON = new ObjectMapper();
    private OverthinkingNotificationIdentity() { }

    private static final String RELATIONS = """
        join tbl_overthinking_post p on p.id=r.post_id and p.author_id=r.author_id
        join tbl_user author on author.id=r.author_id and author.erased_at is null
          and author.status='ACTIVE' and author.email_verified
        join tbl_user requester on requester.id=r.requester_id and requester.erased_at is null
          and requester.status='ACTIVE' and requester.email_verified
        """;
    private static final String AUTHORITY = """
        r.author_id<>r.requester_id and p.visibility_type='ANONYMOUS'
        and (not exists(select 1 from user_roles ur join tbl_role role on role.id=ur.role_id
          where ur.user_id in (r.author_id,r.requester_id) and role.name='ROLE_LISTENER')
          or (%s))
        """.formatted(OverthinkingMainstageVisibility.sql("p"));
    private static final String RECIPIENT = """
        case :type
          when 'OVERTHINKING_REVEAL_REQUEST_RECEIVED' then r.author_id=:recipient
            and (not :native or r.status='PENDING')
          when 'OVERTHINKING_REVEAL_REQUEST_APPROVED' then r.requester_id=:recipient and r.status='APPROVED'
          when 'OVERTHINKING_REVEAL_REQUEST_REJECTED' then r.requester_id=:recipient and r.status='REJECTED'
          else false end
        """;

    /** Read identities first, then the caller locks both accounts before parent/child. */
    public static List<UUID> participants(NamedParameterJdbcTemplate jdbc, Map<String,Object> payload) {
        UUID request=NotificationDeliveryPolicy.uuid(payload,"revealRequestId"), post=NotificationDeliveryPolicy.uuid(payload,"postId");
        if (request==null || post==null) return List.of();
        return jdbc.query("select author_id,requester_id from tbl_overthinking_reveal_request where id=:request and post_id=:post",
            Map.of("request",request,"post",post),rs->{
                if (!rs.next()) return List.of();
                return List.of(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class));
            });
    }

    /** Consumer and send-time source fencing; called inside the existing transaction. */
    public static boolean eligible(NamedParameterJdbcTemplate jdbc, NotificationInboundEvent event) {
        if (event.eventId()==null || event.occurredAt()==null || !validPayload(event.type(),event.payload())) return false;
        var args=new HashMap<String,Object>();
        args.put("source",event.eventId());args.put("recipient",event.recipientId());args.put("type",event.type().name());
        args.put("native",true);args.put("occurred",Timestamp.from(event.occurredAt()));
        try { args.put("payload",JSON.writeValueAsString(event.payload())); }
        catch (java.io.IOException invalid) { return false; }
        return !jdbc.query("""
            select r.id from tbl_overthinking_notification_outbox o
            join tbl_overthinking_reveal_request r on r.id::text=o.payload->>'revealRequestId'
            """+RELATIONS+"""
            where o.event_id=:source and o.recipient_id=:recipient and o.notification_type=:type
              and o.occurred_at=:occurred and o.payload=cast(:payload as jsonb)
              and p.id::text=o.payload->>'postId'
              and (o.notification_type<>'OVERTHINKING_REVEAL_REQUEST_RECEIVED' or r.requester_id::text=o.payload->>'requesterId')
              and (o.notification_type<>'OVERTHINKING_REVEAL_REQUEST_APPROVED' or r.author_id::text=o.payload->>'authorId')
              and
            """+AUTHORITY+" and "+RECIPIENT+" for share of p,r",
            args,(rs,index)->rs.getObject(1,UUID.class)).isEmpty();
    }

    public record Owned(NotificationResponseDto notification, UUID sourceEventId, Instant occurredAt,
                        Map<String,Object> sourcePayload, boolean hasSource) { }

    public static Optional<Owned> resolve(NamedParameterJdbcTemplate jdbc, UUID recipient, UUID notification, boolean nativePush) {
        if (recipient==null || notification==null) return Optional.empty();
        var rows=jdbc.query("""
            select n.id,n.recipient_id,n.source_event_id,n.type,n.is_read,n.occurred_at,n.payload::text,
              r.status,r.id request_id,p.id post_id,o.event_id is not null has_source
            from tbl_notification n
            join tbl_notification_receipt receipt on receipt.source_event_id=n.source_event_id and receipt.recipient_id=n.recipient_id
            join tbl_overthinking_reveal_request r on r.id::text=n.payload->>'revealRequestId'
            """+RELATIONS+"""
            left join tbl_overthinking_notification_outbox o on o.event_id=n.source_event_id
            where n.id=:notification and n.recipient_id=:recipient and n.occurred_at is not null
              and n.payload->>'module'='OVERTHINKING' and n.payload->>'action'=substring(n.type from 14)
              and p.id::text=n.payload->>'postId'
              and (not :native or (not n.is_read and o.event_id is not null))
              and (o.event_id is null or (o.recipient_id=n.recipient_id and o.notification_type=n.type
                and o.occurred_at=n.occurred_at and o.payload=n.payload))
              and (n.type<>'OVERTHINKING_REVEAL_REQUEST_RECEIVED' or r.requester_id::text=n.payload->>'requesterId')
              and (n.type<>'OVERTHINKING_REVEAL_REQUEST_APPROVED' or r.author_id::text=n.payload->>'authorId')
              and
            """+AUTHORITY+" and "+RECIPIENT.replace(":type","n.type"),
            Map.of("notification",notification,"recipient",recipient,"native",nativePush),(rs,index)->{
                Map<String,Object> payload;
                try { payload=JSON.readValue(rs.getString("payload"),new TypeReference<>(){}); }
                catch(java.io.IOException invalid) { return null; }
                var type=NotificationType.valueOf(rs.getString("type"));
                if(!validPayload(type,payload)) return null;
                var time=rs.getTimestamp("occurred_at").toInstant();
                var source=rs.getObject("source_event_id",UUID.class);
                // The fresh target returns no anonymous author/requester identity or private snapshot text.
                Map<String,Object> target=Map.of("module","OVERTHINKING","action",type.name().substring(13),
                    "postId",rs.getString("post_id"),"revealRequestId",rs.getString("request_id"),
                    "requestStatus",rs.getString("status"),"sourceEventId",source.toString(),"identityVersion",1);
                return new Owned(new NotificationResponseDto(notification,recipient,type,"Anonim paylaşım bildirimi","",
                    rs.getBoolean("is_read"),time,target),source,time,Map.copyOf(payload),rs.getBoolean("has_source"));
            });
        return rows.size()==1?Optional.ofNullable(rows.getFirst()):Optional.empty();
    }

    public static boolean matches(Owned owned, Notification n) {
        return n!=null && !n.isRead() && owned.notification().id().equals(n.getId())
            && owned.notification().recipientId().equals(n.getRecipientId()) && owned.notification().type()==n.getType()
            && owned.sourceEventId().equals(n.getSourceEventId()) && n.getOccurredAt()!=null
            && owned.occurredAt().isAfter(n.getOccurredAt().minusNanos(1000))
            && owned.occurredAt().isBefore(n.getOccurredAt().plusNanos(1000))
            && JSON.valueToTree(owned.sourcePayload()).equals(JSON.valueToTree(n.getPayload()));
    }

    private static boolean validPayload(NotificationType type,Map<String,Object> payload) {
        if(!TYPES.contains(type) || payload==null) return false;
        var keys=new HashSet<>(Set.of("module","action","postId","postTitle","revealRequestId"));
        if(type==NotificationType.OVERTHINKING_REVEAL_REQUEST_RECEIVED) keys.add("requesterId");
        if(type==NotificationType.OVERTHINKING_REVEAL_REQUEST_APPROVED) keys.add("authorId");
        if(!payload.keySet().equals(keys) || !payload.values().stream().allMatch(String.class::isInstance)
            || !"OVERTHINKING".equals(payload.get("module")) || !type.name().substring(13).equals(payload.get("action"))) return false;
        for(String key:keys) if(key.endsWith("Id")) {
            String value=(String)payload.get(key);
            try { if(!UUID.fromString(value).toString().equals(value)) return false; }
            catch(IllegalArgumentException invalid) { return false; }
        }
        return true;
    }
}
