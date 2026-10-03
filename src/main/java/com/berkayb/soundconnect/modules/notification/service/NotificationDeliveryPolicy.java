package com.berkayb.soundconnect.modules.notification.service;

import com.berkayb.soundconnect.modules.notification.support.BandNotificationIdentity;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.notification.support.NotificationAudiencePolicy;
import com.berkayb.soundconnect.modules.notification.support.MediaNotificationIdentity;
import com.berkayb.soundconnect.modules.notification.support.OverthinkingNotificationIdentity;
import com.berkayb.soundconnect.modules.user.support.AccountDeliveryFence;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationInboundEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

@Service
@RequiredArgsConstructor
public class NotificationDeliveryPolicy {
    private final AccountDeliveryFence accounts;
    private final NamedParameterJdbcTemplate jdbc;
    private final PlatformTransactionManager transactions;
    private final AfterCommitDeliveryExecutor deliveryExecutor;

    /** Lock order: accounts, source aggregate then child, receipt/inbox at the caller. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean eligible(NotificationInboundEvent event) {
        if(event==null || event.recipientId()==null || event.type()==null) return false;
        boolean media=MediaNotificationIdentity.applies(event.type(),event.payload());
        Set<UUID> referenced=new LinkedHashSet<>();
        if(media) MediaNotificationIdentity.actorId(event.payload()).ifPresent(referenced::add);
        else if(BandNotificationIdentity.applies(event.type())) BandNotificationIdentity.actorId(event.type(),event.payload()).ifPresent(referenced::add);
        else collectIds(event.payload(),referenced,0);
        if (OverthinkingNotificationIdentity.TYPES.contains(event.type()))
            referenced.addAll(OverthinkingNotificationIdentity.participants(jdbc,event.payload()));
        if(!accounts.canDeliver(event.recipientId(),referenced)) return false;
        if(media && !referenced.isEmpty() && !Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from tbl_user where id=:actor and erased_at is null
                  and status='ACTIVE' and email_verified)
                """, Map.of("actor",referenced.iterator().next()),Boolean.class))) return false;
        // canDeliver holds the existing account fence through receipt insertion
        // and through final dispatch. Re-read roles here, never a sender/JWT snapshot.
        if(NotificationAudiencePolicy.businessOnly(event.type()) && Boolean.TRUE.equals(jdbc.queryForObject(
                NotificationAudiencePolicy.LISTENER_SQL, Map.of("recipient", event.recipientId()), Boolean.class))) return false;
        if(event.type()==NotificationType.DM_NEW_MESSAGE) return eligibleUnreadDm(event);
        if(com.berkayb.soundconnect.modules.notification.push.VenueApplicationPushPresentation.TYPES.contains(event.type()))
            return new com.berkayb.soundconnect.modules.application.venueapplication.service.VenueApplicationDecisionEligibility(jdbc).eligible(event);
        if(!"OVERTHINKING".equals(event.type().getCategory())) return true;
        return OverthinkingNotificationIdentity.eligible(jdbc,event);
    }

    private boolean eligibleUnreadDm(NotificationInboundEvent event) {
        UUID messageId=uuid(event.payload(),"messageId"), conversationId=uuid(event.payload(),"conversationId"),
                senderId=uuid(event.payload(),"senderId"), recipientId=uuid(event.payload(),"recipientId");
        if(messageId==null || conversationId==null || senderId==null || !event.recipientId().equals(recipientId)) return false;
        // Serialize admission with whole-conversation moderation, and reject
        // orphaned/forged messages even when a legacy row survived deletion.
        if(jdbc.query("""
                select id from tbl_dm_conversation where id=:conversationId
                  and ((user_a_id=:senderId and user_b_id=:recipientId)
                    or (user_a_id=:recipientId and user_b_id=:senderId)) for share
                """, Map.of("conversationId",conversationId,"senderId",senderId,"recipientId",recipientId),
                (rs,row) -> rs.getObject("id",UUID.class)).isEmpty()) return false;
        // Never trust a queued creation snapshot after a read/delete. Holding
        // the source lock until inbox insertion (or final dispatch) makes a
        // concurrent explicit read wait and then mark the admitted row read.
        return !jdbc.query("""
                select id from tbl_dm_message
                where id=:messageId and conversation_id=:conversationId
                  and sender_id=:senderId and recipient_id=:recipientId
                  and read_at is null and deleted_at is null
                for share
                """, Map.of("messageId",messageId,"conversationId",conversationId,
                "senderId",senderId,"recipientId",recipientId), (rs,row) -> rs.getObject("id",UUID.class)).isEmpty();
    }

    public void schedule(NotificationInboundEvent event, UUID notificationId, Runnable work) {
        deliveryExecutor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            if(!eligible(event)) return;
            if(!Boolean.TRUE.equals(jdbc.queryForObject(
                    "select exists(select 1 from tbl_notification where id=:id and recipient_id=:recipient"
                            + (MediaNotificationIdentity.applies(event.type(),event.payload()) ? " and not is_read" : "") + ")",
                    Map.of("id",notificationId,"recipient",event.recipientId()),Boolean.class))) return;
            work.run();
        }));
    }

    public static UUID uuid(Map<String,Object> payload,String key) {
        if(payload==null) return null;
        Object value=payload.get(key);
        try { return value==null?null:UUID.fromString(value.toString()); }
        catch(IllegalArgumentException invalid) { return null; }
    }
    private void collectIds(Object value,Set<UUID> ids,int depth) {
        if(value==null) return;
        if(depth>8 || ids.size()>1000) throw new IllegalArgumentException("Notification identity references exceed bounds");
        if(value instanceof Map<?,?> map) { map.values().forEach(item -> collectIds(item,ids,depth+1)); return; }
        if(value instanceof Collection<?> list) { list.forEach(item -> collectIds(item,ids,depth+1)); return; }
        try { ids.add(value instanceof UUID id?id:UUID.fromString(value.toString())); }
        catch(IllegalArgumentException ignored) { }
    }
}
