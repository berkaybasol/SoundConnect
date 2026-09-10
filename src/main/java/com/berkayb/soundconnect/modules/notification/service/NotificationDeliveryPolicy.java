package com.berkayb.soundconnect.modules.notification.service;

import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
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

    /** Lock order: accounts, source post/request, then receipt/inbox at the caller. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean eligible(NotificationInboundEvent event) {
        if(event==null || event.recipientId()==null || event.type()==null) return false;
        Set<UUID> referenced=new LinkedHashSet<>(); collectIds(event.payload(),referenced,0);
        if(!accounts.canDeliver(event.recipientId(),referenced)) return false;
        if(!"OVERTHINKING".equals(event.type().getCategory())) return true;
        UUID postId=uuid(event.payload(),"postId"), requestId=uuid(event.payload(),"revealRequestId");
        if(postId==null || requestId==null) return false;
        // Shared source locks prevent post deletion/request withdrawal from
        // overtaking a validated receipt insertion or final delivery.
        var rows=jdbc.query("""
                select r.status, r.requester_id, r.author_id
                from tbl_overthinking_post p join tbl_overthinking_reveal_request r on r.post_id=p.id
                where p.id=:postId and r.id=:requestId for share of p,r
                """,Map.of("postId",postId,"requestId",requestId),(rs,row) -> new Source(rs.getString("status"),
                        rs.getObject("requester_id",UUID.class),rs.getObject("author_id",UUID.class)));
        if(rows.isEmpty()) return false;
        var source=rows.getFirst();
        return switch(event.type()) {
            case OVERTHINKING_REVEAL_REQUEST_RECEIVED -> "PENDING".equals(source.status()) && event.recipientId().equals(source.author());
            case OVERTHINKING_REVEAL_REQUEST_APPROVED -> "APPROVED".equals(source.status()) && event.recipientId().equals(source.requester());
            case OVERTHINKING_REVEAL_REQUEST_REJECTED -> "REJECTED".equals(source.status()) && event.recipientId().equals(source.requester());
            default -> false;
        };
    }

    public void schedule(NotificationInboundEvent event, UUID notificationId, Runnable work) {
        deliveryExecutor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            if(!eligible(event)) return;
            if(!Boolean.TRUE.equals(jdbc.queryForObject(
                    "select exists(select 1 from tbl_notification where id=:id and recipient_id=:recipient)",
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
    private record Source(String status,UUID requester,UUID author) { }
}
