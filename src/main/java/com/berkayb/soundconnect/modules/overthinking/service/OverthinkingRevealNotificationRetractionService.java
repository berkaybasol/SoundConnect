package com.berkayb.soundconnect.modules.overthinking.service;

import com.berkayb.soundconnect.modules.notification.helper.NotificationBadgeCacheHelper;
import com.berkayb.soundconnect.modules.notification.websocket.NotificationWebSocketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;
import java.util.LinkedHashSet;

@Service
@RequiredArgsConstructor
@Slf4j
public class OverthinkingRevealNotificationRetractionService {
    private final JdbcTemplate jdbc;
    private final NotificationBadgeCacheHelper badges;
    private final NotificationWebSocketService websocket;

    @Transactional(propagation = Propagation.MANDATORY)
    public void retract(UUID authorId, UUID requestId) {
        // Claim the original event receipts before clearing its notification.
        // This serializes with an already-running consumer and also suppresses
        // delayed broker delivery/replay after cancellation, without mutating
        // the outbox's delivery audit or affecting a later re-request event.
        jdbc.update("""
                insert into tbl_notification_receipt(source_event_id, recipient_id, recorded_at)
                select event_id, recipient_id, current_timestamp from tbl_overthinking_notification_outbox
                where recipient_id=? and notification_type='OVERTHINKING_REVEAL_REQUEST_RECEIVED'
                  and payload->>'revealRequestId'=?
                on conflict do nothing
                """, authorId, requestId.toString());
        jdbc.update("""
                insert into tbl_notification_receipt(source_event_id, recipient_id, recorded_at)
                select source_event_id, recipient_id, current_timestamp from tbl_notification
                where recipient_id=? and type='OVERTHINKING_REVEAL_REQUEST_RECEIVED'
                  and payload->>'revealRequestId'=? and source_event_id is not null
                on conflict do nothing
                """, authorId, requestId.toString());
        jdbc.update("""
                delete from tbl_notification where recipient_id=?
                  and type='OVERTHINKING_REVEAL_REQUEST_RECEIVED' and payload->>'revealRequestId'=?
                """, authorId, requestId.toString());
        // Even before notification delivery the author may have fetched the
        // request over HTTP. Always invalidate connected inbox projections.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { projectUnread(authorId); }
        });
    }

    /** Caller holds the source write lock; receipts fence claimed or already queued broker deliveries. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void retractPost(UUID authorId, UUID postId) {
        String source = postId.toString();
        var recipients = new LinkedHashSet<UUID>(jdbc.queryForList("""
                select recipient_id from tbl_overthinking_notification_outbox where payload->>'postId'=?
                union select recipient_id from tbl_notification
                where type in ('OVERTHINKING_REVEAL_REQUEST_RECEIVED','OVERTHINKING_REVEAL_REQUEST_APPROVED','OVERTHINKING_REVEAL_REQUEST_REJECTED')
                    and payload->>'postId'=?
                """, UUID.class, source, source));
        recipients.add(authorId);
        jdbc.update("""
                insert into tbl_notification_receipt(source_event_id,recipient_id,recorded_at)
                select event_id,recipient_id,current_timestamp from tbl_overthinking_notification_outbox
                where payload->>'postId'=? on conflict do nothing
                """, source);
        jdbc.update("""
                insert into tbl_notification_receipt(source_event_id,recipient_id,recorded_at)
                select source_event_id,recipient_id,current_timestamp from tbl_notification
                where type in ('OVERTHINKING_REVEAL_REQUEST_RECEIVED','OVERTHINKING_REVEAL_REQUEST_APPROVED','OVERTHINKING_REVEAL_REQUEST_REJECTED')
                    and payload->>'postId'=? and source_event_id is not null on conflict do nothing
                """, source);
        jdbc.update("""
                delete from tbl_notification
                where type in ('OVERTHINKING_REVEAL_REQUEST_RECEIVED','OVERTHINKING_REVEAL_REQUEST_APPROVED','OVERTHINKING_REVEAL_REQUEST_REJECTED')
                    and payload->>'postId'=?
                """, source);
        jdbc.update("delete from tbl_overthinking_notification_outbox where payload->>'postId'=?", source);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { recipients.forEach(OverthinkingRevealNotificationRetractionService.this::projectUnread); }
        });
    }

    private void projectUnread(UUID authorId) {
        final long unread;
        try {
            unread = jdbc.queryForObject("select count(*) from tbl_notification where recipient_id=? and is_read=false", Long.class, authorId);
        } catch (RuntimeException exception) {
            log.warn("Cancelled reveal notification recount failed for author={}", authorId);
            return;
        }
        try { badges.setUnread(authorId, unread); }
        catch (RuntimeException exception) { log.warn("Cancelled reveal notification cache refresh failed for author={}", authorId); }
        try { websocket.sendUnreadBadgeToUser(authorId, unread); }
        catch (RuntimeException exception) { log.warn("Cancelled reveal notification badge delivery failed for author={}", authorId); }
    }
}
