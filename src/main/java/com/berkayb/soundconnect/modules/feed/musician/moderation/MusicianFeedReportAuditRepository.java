package com.berkayb.soundconnect.modules.feed.musician.moderation;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class MusicianFeedReportAuditRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public MusicianFeedReportAuditRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<String> requestHash(UUID reportId, UUID requestId) {
        return jdbc.queryForList("""
                select request_hash from tbl_musician_feed_report_audit
                where report_id=:reportId and client_request_id=:requestId
                """, new MapSqlParameterSource("reportId", reportId).addValue("requestId", requestId),
                String.class).stream().findFirst();
    }

    public List<MusicianFeedReportHistoryEntry> history(UUID reportId) {
        // The acyclic state machine permits at most three transitions per report.
        return jdbc.query("""
                select id,decision,previous_status,status,actor_user_id,occurred_at,resolution_note
                from tbl_musician_feed_report_audit where report_id=:reportId order by resulting_version
                """, new MapSqlParameterSource("reportId", reportId), (row, index) ->
                new MusicianFeedReportHistoryEntry(row.getObject("id", UUID.class),
                        MusicianFeedReportDecision.valueOf(row.getString("decision")),
                        MusicianFeedReportStatus.valueOf(row.getString("previous_status")),
                        MusicianFeedReportStatus.valueOf(row.getString("status")),
                        row.getObject("actor_user_id", UUID.class), row.getTimestamp("occurred_at").toInstant(),
                        row.getString("resolution_note")));
    }

    public void append(UUID reportId, UUID requestId, String hash, long version, MusicianFeedReportDecision decision,
            MusicianFeedReportStatus previous, MusicianFeedReportStatus status, UUID actor, String note, Instant now) {
        jdbc.update("""
                insert into tbl_musician_feed_report_audit(id,report_id,client_request_id,request_hash,
                    resulting_version,decision,previous_status,status,actor_user_id,occurred_at,resolution_note)
                values(:id,:reportId,:requestId,:hash,:version,:decision,:previous,:status,:actor,:now,:note)
                """, new MapSqlParameterSource("id", UUID.randomUUID()).addValue("reportId", reportId)
                .addValue("requestId", requestId).addValue("hash", hash).addValue("version", version)
                .addValue("decision", decision.name()).addValue("previous", previous.name())
                .addValue("status", status.name()).addValue("actor", actor).addValue("note", note)
                .addValue("now", Timestamp.from(now)));
    }
}
