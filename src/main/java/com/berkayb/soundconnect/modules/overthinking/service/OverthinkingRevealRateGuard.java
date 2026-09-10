package com.berkayb.soundconnect.modules.overthinking.service;

import com.berkayb.soundconnect.shared.exception.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.UUID;

/** Committed history survives withdrawal; requester-only quotas cannot link anonymous authors by their 429 responses. */
@Service @RequiredArgsConstructor
public class OverthinkingRevealRateGuard {
    private final JdbcTemplate jdbc;

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockRequester(UUID requester) {
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?,0))", Object.class,
                "overthinking:reveal:" + requester);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void reserve(UUID requester, UUID author) {
        Long retryAfter = jdbc.queryForObject("""
                with clock as (select clock_timestamp() as now), recent as (
                    select attempt.created_at, clock.now
                    from tbl_overthinking_reveal_attempt attempt cross join clock
                    where attempt.requester_id=? and attempt.created_at > clock.now - interval '10 minutes'
                )
                select greatest(
                    case when count(*) >= 10 then ceil(extract(epoch from min(created_at)+interval '10 minutes'-max(now))) else 0 end,
                    coalesce(ceil(extract(epoch from max(created_at)+interval '60 seconds'-max(now))),0)
                )::bigint from recent
                """, Long.class, requester);
        if (retryAfter != null && retryAfter > 0)
            throw new RateLimitedException(ErrorType.OVERTHINKING_REVEAL_RATE_LIMITED, retryAfter);
        jdbc.update("insert into tbl_overthinking_reveal_attempt(id,requester_id,author_id,created_at) values(?,?,?,clock_timestamp())",
                UUID.randomUUID(), requester, author);
    }

    @Scheduled(cron = "${app.overthinking.reveal-rate-cleanup-cron:0 10 4 * * *}")
    @Transactional
    public void cleanup() {
        jdbc.update("delete from tbl_overthinking_reveal_attempt where created_at < clock_timestamp()-interval '1 day'");
    }
}
