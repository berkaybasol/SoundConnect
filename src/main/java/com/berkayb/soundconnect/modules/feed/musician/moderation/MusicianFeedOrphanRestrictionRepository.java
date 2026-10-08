package com.berkayb.soundconnect.modules.feed.musician.moderation;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class MusicianFeedOrphanRestrictionRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public MusicianFeedOrphanRestrictionRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Row> page(Instant anchor, MusicianFeedOrphanRestrictionCursor.Position position, int limit) {
        var parameters = new MapSqlParameterSource("anchor", Timestamp.from(anchor)).addValue("limit", limit);
        String boundary = "";
        if (position != null) {
            boundary = " and (restriction.applied_at,restriction.report_id)<(:appliedAt,:id)";
            parameters.addValue("appliedAt", Timestamp.from(position.appliedAt())).addValue("id", position.reportId());
        }
        return jdbc.query("""
                select restriction.report_id,restriction.scope_key,restriction.active,
                    restriction.applied_by_user_id,restriction.applied_at,restriction.updated_at
                from tbl_musician_feed_restriction restriction
                where restriction.active and restriction.orphaned and restriction.applied_at<=:anchor
                    and not exists(select 1 from tbl_musician_feed_content_report report
                        where report.id=restriction.report_id)
                """ + boundary + " order by restriction.applied_at desc,restriction.report_id desc limit :limit",
                parameters, (row, index) -> row(row));
    }

    public Optional<Row> lock(UUID reportId) {
        return jdbc.query("""
                select report_id,scope_key,active,applied_by_user_id,applied_at,updated_at
                from tbl_musician_feed_restriction where report_id=:id for update
                """, new MapSqlParameterSource("id", reportId), (row, index) -> row(row)).stream().findFirst();
    }

    public boolean reportExists(UUID reportId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from tbl_musician_feed_content_report where id=:id)",
                new MapSqlParameterSource("id", reportId), Boolean.class));
    }

    public Optional<String> requestHash(UUID reportId, UUID clientRequestId) {
        return jdbc.queryForList("""
                select request_hash from tbl_musician_feed_restriction_restore_audit
                where report_id=:report and client_request_id=:request
                """, new MapSqlParameterSource("report", reportId).addValue("request", clientRequestId), String.class)
                .stream().findFirst();
    }

    public void append(UUID reportId, UUID requestId, String hash, UUID actor, String note, Instant now) {
        jdbc.update("""
                insert into tbl_musician_feed_restriction_restore_audit(
                    id,report_id,client_request_id,request_hash,actor_user_id,resolution_note,restored_at)
                values(:id,:report,:request,:hash,:actor,:note,:now)
                """, new MapSqlParameterSource("id", UUID.randomUUID()).addValue("report", reportId)
                .addValue("request", requestId).addValue("hash", hash).addValue("actor", actor)
                .addValue("note", note).addValue("now", Timestamp.from(now)));
    }

    private Row row(ResultSet row) throws SQLException {
        return new Row(row.getObject("report_id", UUID.class), row.getString("scope_key"), row.getBoolean("active"),
                row.getObject("applied_by_user_id", UUID.class), row.getTimestamp("applied_at").toInstant(), row.getTimestamp("updated_at").toInstant());
    }

    public record Row(UUID reportId, String scopeKey, boolean active, UUID appliedByUserId, Instant appliedAt, Instant updatedAt) { }
}
