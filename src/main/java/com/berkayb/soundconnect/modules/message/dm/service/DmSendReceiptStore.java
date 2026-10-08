package com.berkayb.soundconnect.modules.message.dm.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Content-free receipts survive moderation, so a delayed retry cannot recreate deleted content. */
@Repository
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class DmSendReceiptStore {
    private final NamedParameterJdbcTemplate jdbc;

    public void lock(UUID sender, UUID clientMessageId) {
        if (clientMessageId == null) return;
        // Across application nodes and across conversations. A hash collision
        // only adds serialization; the primary key still compares both UUIDs.
        jdbc.queryForObject("select 1 from pg_advisory_xact_lock(hashtextextended(:key,0))",
                Map.of("key", "dm-send:" + sender + ":" + clientMessageId), Integer.class);
    }

    public Optional<Receipt> find(UUID sender, UUID clientMessageId) {
        if (clientMessageId == null) return Optional.empty();
        return jdbc.query("""
                select conversation_id, recipient_id, message_id from tbl_dm_send_receipt
                where sender_id=:sender and client_message_id=:client
                """, Map.of("sender", sender, "client", clientMessageId),
                (rs, row) -> new Receipt(rs.getObject("conversation_id", UUID.class),
                        rs.getObject("recipient_id", UUID.class), rs.getObject("message_id", UUID.class)))
                .stream().findFirst();
    }

    public void record(UUID sender, UUID clientMessageId, UUID conversation, UUID recipient, UUID message) {
        if (clientMessageId == null) return;
        jdbc.update("""
                insert into tbl_dm_send_receipt(sender_id,client_message_id,conversation_id,recipient_id,message_id)
                values (:sender,:client,:conversation,:recipient,:message)
                """, Map.of("sender", sender, "client", clientMessageId, "conversation", conversation,
                "recipient", recipient, "message", message));
    }
    public record Receipt(UUID conversationId, UUID recipientId, UUID messageId) { }
}
