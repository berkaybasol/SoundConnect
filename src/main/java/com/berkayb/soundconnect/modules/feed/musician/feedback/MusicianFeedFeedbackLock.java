package com.berkayb.soundconnect.modules.feed.musician.feedback;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Serializes idempotent feedback scope creation without a process-local lock. */
@Component
public class MusicianFeedFeedbackLock {
    private final JdbcTemplate jdbc;

    public MusicianFeedFeedbackLock(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void viewer(UUID viewerId) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?::text,0))",
                ignored -> { }, viewerId);
    }
}
