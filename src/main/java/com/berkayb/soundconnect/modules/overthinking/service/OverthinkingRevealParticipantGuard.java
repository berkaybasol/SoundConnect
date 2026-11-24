package com.berkayb.soundconnect.modules.overthinking.service;

import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.modules.user.support.AccountDeliveryFence;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/** Fence the other account before taking source/request/profile locks, matching account erasure's order. */
@Component @RequiredArgsConstructor @Transactional(propagation = Propagation.MANDATORY)
public class OverthinkingRevealParticipantGuard {
    private final JdbcTemplate jdbc;
    private final AccountDeliveryFence accounts;

    private record Peer(UUID id, boolean erased) {}

    // Delivery takes account -> contact pair -> source/request locks. Decisions, withdrawals
    // and creation must follow the same order, including the first request for a post.
    public boolean lockPostAuthor(UUID requester, UUID postId) {
        var authors = jdbc.query("""
                select u.id, u.erased_at is not null as erased from tbl_user u
                join tbl_overthinking_post p on p.author_id=u.id
                where p.id=? for share of u
                """, (row, index) -> new Peer(row.getObject("id", UUID.class), row.getBoolean("erased")), postId);
        return lockPeer(requester, authors, ErrorType.OVERTHINKING_POST_NOT_FOUND);
    }

    public void lockRequesterForDecision(UUID authorId, UUID requestId) {
        var requesters = jdbc.query("""
                select u.id, u.erased_at is not null as erased from tbl_user u
                join tbl_overthinking_reveal_request r on r.requester_id=u.id
                where r.id=? and r.author_id=? for share of u
                """, (row, index) -> new Peer(row.getObject("id", UUID.class), row.getBoolean("erased")), requestId, authorId);
        if (lockPeer(authorId, requesters, ErrorType.OVERTHINKING_REVEAL_REQUEST_NOT_FOUND))
            throw new SoundConnectException(ErrorType.OVERTHINKING_REVEAL_REQUEST_NOT_FOUND);
    }

    private boolean lockPeer(UUID actor, java.util.List<Peer> rows, ErrorType notFound) {
        if (rows.isEmpty()) return false;
        var peer = rows.getFirst();
        if (peer.erased()) throw new SoundConnectException(notFound);
        if (actor.equals(peer.id())) return false;
        accounts.lockInteraction(actor, peer.id());
        return accounts.blockStatus(actor, peer.id()).interactionBlocked();
    }
}
