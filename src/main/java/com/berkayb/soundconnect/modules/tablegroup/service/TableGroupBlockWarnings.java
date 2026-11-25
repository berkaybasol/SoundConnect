package com.berkayb.soundconnect.modules.tablegroup.service;

import com.berkayb.soundconnect.modules.notification.service.AfterCommitDeliveryExecutor;
import com.berkayb.soundconnect.modules.tablegroup.chat.cache.TableGroupChatUnreadHelper;
import com.berkayb.soundconnect.modules.user.block.UserBlockService;
import com.berkayb.soundconnect.shared.realtime.WebSocketChannels;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class TableGroupBlockWarnings {
    private final NamedParameterJdbcTemplate jdbc;
    private final AfterCommitDeliveryExecutor deliveries;
    private final SimpMessagingTemplate messaging;
    private final TableGroupChatUnreadHelper unread;

    /** Compare against the block writer's DB clock after the user and table locks have been acquired. */
    public Instant acceptanceTime() {
        return jdbc.getJdbcTemplate().queryForObject("select clock_timestamp()",
                (rs, row) -> rs.getTimestamp(1).toInstant());
    }

    // Only an outgoing block is a reason to warn this particular viewer.
    public Map<UUID, Boolean> forViewer(UUID tableId, UUID viewerId) {
        return jdbc.query("""
                select member.user_id,
                       (viewer.user_id is not null and member.accepted_at is not null
                        and (viewer.accepted_at is null or viewer.accepted_at < member.accepted_at)
                        and b.created_at <= member.accepted_at) as arrived_later
                from tbl_table_group t
                join tbl_table_group_participants member on member.table_group_id=t.id and member.status='ACCEPTED'
                join tbl_user_block b on b.blocker_id=:viewer and b.blocked_id=member.user_id and b.unblocked_at is null
                left join tbl_table_group_participants viewer on viewer.table_group_id=t.id
                      and viewer.user_id=:viewer and viewer.status='ACCEPTED'
                where t.id=:tableId and t.status='ACTIVE' and t.expires_at > current_timestamp
                """, Map.of("tableId", tableId, "viewer", viewerId), rs -> {
            Map<UUID, Boolean> members = new LinkedHashMap<>();
            while (rs.next()) members.put(rs.getObject("user_id", UUID.class), rs.getBoolean("arrived_later"));
            return members;
        });
    }

    // The private signal wakes an open screen. A fresh detail read is authoritative after reconnect.
    public void notifyAccepted(UUID tableId, UUID memberId, UUID applicationId) {
        deliveries.submit(() -> {
            List<UUID> recipients = jdbc.queryForList("""
                    select viewer.user_id
                    from tbl_table_group t
                    join tbl_table_group_participants member on member.table_group_id=t.id and member.user_id=:member
                         and member.application_id is not distinct from cast(:application as uuid) and member.status='ACCEPTED'
                    join tbl_table_group_participants viewer on viewer.table_group_id=t.id and viewer.status='ACCEPTED'
                         and (viewer.accepted_at is null or viewer.accepted_at < member.accepted_at)
                    join tbl_user_block b on b.blocker_id=viewer.user_id and b.blocked_id=member.user_id
                         and b.unblocked_at is null and b.created_at <= member.accepted_at
                    where t.id=:tableId and t.status='ACTIVE' and t.expires_at > current_timestamp
                    """, new MapSqlParameterSource("tableId", tableId).addValue("member", memberId)
                    .addValue("application", applicationId), UUID.class);
            Map<String, Object> payload = new LinkedHashMap<>(Map.of(
                    "type", "BLOCKED_MEMBER_JOINED", "tableGroupId", tableId, "userId", memberId));
            if (applicationId != null) payload.put("applicationId", applicationId);
            for (UUID recipient : recipients) {
                messaging.convertAndSend(WebSocketChannels.tableGroup(tableId, recipient), payload);
            }
        });
    }

    @TransactionalEventListener
    public void relationshipChanged(UserBlockService.RelationshipChanged change) {
        deliveries.submit(() -> {
            List<UUID> sharedTables = jdbc.queryForList("""
                    select t.id from tbl_table_group t
                    join tbl_table_group_participants a on a.table_group_id=t.id and a.user_id=:actor and a.status='ACCEPTED'
                    join tbl_table_group_participants b on b.table_group_id=t.id and b.user_id=:target and b.status='ACCEPTED'
                    where t.status='ACTIVE' and t.expires_at > current_timestamp
                    """, Map.of("actor", change.actor(), "target", change.target()), UUID.class);
            for (UUID tableId : sharedTables) {
                for (UUID recipient : List.of(change.actor(), change.target())) {
                    // The legacy unread cache has one aggregate count per recipient.
                    if (change.blocked()) unread.resetUnread(recipient, tableId);
                    messaging.convertAndSend(WebSocketChannels.tableGroup(tableId, recipient),
                            Map.of("type", "TABLE_VISIBILITY_CHANGED", "tableGroupId", tableId));
                }
            }
        });
    }
}
