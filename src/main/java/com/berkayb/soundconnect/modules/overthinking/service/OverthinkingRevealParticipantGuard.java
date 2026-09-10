package com.berkayb.soundconnect.modules.overthinking.service;

import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
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

    public void lockPostAuthor(UUID postId) {
        var erased = jdbc.queryForList("""
                select u.erased_at is not null from tbl_user u
                join tbl_overthinking_post p on p.author_id=u.id
                where p.id=? for share of u
                """, Boolean.class, postId);
        if (erased.contains(true)) throw new SoundConnectException(ErrorType.OVERTHINKING_POST_NOT_FOUND);
    }

    public void lockRequesterForDecision(UUID authorId, UUID requestId) {
        var erased = jdbc.queryForList("""
                select u.erased_at is not null from tbl_user u
                join tbl_overthinking_reveal_request r on r.requester_id=u.id
                where r.id=? and r.author_id=? for share of u
                """, Boolean.class, requestId, authorId);
        if (erased.contains(true)) throw new SoundConnectException(ErrorType.OVERTHINKING_REVEAL_REQUEST_NOT_FOUND);
    }
}
