package com.berkayb.soundconnect.modules.overthinking.service;

import com.berkayb.soundconnect.modules.overthinking.dto.response.OverthinkingIncomingUnreadStatusResponseDto;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Durable inbox state is independent of decisions, notification delivery and retention. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OverthinkingRevealInboxService {
    private final JdbcTemplate jdbc;
    private final UserEntityFinder users;

    public OverthinkingIncomingUnreadStatusResponseDto getUnreadStatus(UUID authorId) {
        requireUser(authorId);
        return snapshot(authorId);
    }

    @Transactional
    public OverthinkingIncomingUnreadStatusResponseDto markSeen(UUID authorId, long revision) {
        requireUser(authorId);
        if (revision < 0) throw new SoundConnectException(ErrorType.VALIDATION_ERROR);
        // The author-scoped ledger lock also serializes request insertion. An old
        // acknowledgement cannot cover newer arrivals or move the watermark back.
        jdbc.update("""
                update tbl_overthinking_reveal_inbox
                set seen_revision = greatest(seen_revision, least(?, latest_revision))
                where author_id = ?
                """, revision, authorId);
        return snapshot(authorId);
    }

    private OverthinkingIncomingUnreadStatusResponseDto snapshot(UUID authorId) {
        // Both the flag and its acknowledgement boundary come from one database
        // snapshot. Deleted requests/posts do not leave a phantom unread dot.
        return jdbc.queryForObject("""
                select coalesce(inbox.latest_revision, 0) as revision,
                       exists (
                           select 1 from tbl_overthinking_reveal_request request
                           join tbl_overthinking_post post on post.id = request.post_id
                           where request.author_id = ?
                             and request.inbox_revision > coalesce(inbox.seen_revision, 0)
                       ) as has_unread
                from (select 1) singleton
                left join tbl_overthinking_reveal_inbox inbox on inbox.author_id = ?
                """, (row, index) -> new OverthinkingIncomingUnreadStatusResponseDto(
                row.getBoolean("has_unread"), row.getLong("revision")), authorId, authorId);
    }

    private void requireUser(UUID authorId) {
        if (authorId == null) throw new SoundConnectException(ErrorType.UNAUTHORIZED);
        users.getUser(authorId);
    }
}
