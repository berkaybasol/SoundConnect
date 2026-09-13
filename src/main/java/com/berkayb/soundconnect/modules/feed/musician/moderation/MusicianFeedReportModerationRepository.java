package com.berkayb.soundconnect.modules.feed.musician.moderation;

import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedItemType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
public class MusicianFeedReportModerationRepository {
    private static final String SUMMARY = """
            report.id,report.version,report.status,report.item_id,report.item_type,
            report.target_type,report.target_id,report.reason,report.reported_at,
            left(coalesce(nullif(report.evidence_json#>>'{payload,title}',''),
                nullif(report.evidence_json#>>'{payload,event,title}',''),
                nullif(report.evidence_json#>>'{payload,listing,title}',''),
                nullif(report.evidence_json#>>'{payload,targetPayload,title}',''),
                nullif(report.evidence_json#>>'{payload,targetPayload,event,title}',''),
                nullif(report.evidence_json#>>'{payload,targetPayload,listing,title}',''),
                nullif(report.evidence_json#>>'{payload,note}',''),
                nullif(report.evidence_json#>>'{payload,displayName}',''),
                nullif(report.evidence_json#>>'{payload,source,title}',''),
                nullif(report.evidence_json#>>'{payload,source,content}',''),
                nullif(report.evidence_json#>>'{payload,source,description}',''),
                nullif(report.evidence_json#>>'{payload,targetPayload,source,title}',''),
                nullif(report.evidence_json#>>'{payload,targetPayload,source,content}',''),
                nullif(report.evidence_json#>>'{payload,targetPayload,source,description}',''),report.item_type),160) as title,
            left(nullif(report.evidence_json#>>'{author,displayName}',''),255) as author_display_name
            """;
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public MusicianFeedReportModerationRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public List<MusicianFeedReportSummary> page(MusicianFeedReportStatus status, MusicianFeedItemType itemType,
            Instant anchor, MusicianFeedReportCursorCodec.Position position, int limit) {
        var parameters = new MapSqlParameterSource("status", status.name())
                .addValue("anchor", Timestamp.from(anchor)).addValue("limit", limit);
        String where = " where report.status=:status and report.reported_at<=:anchor";
        if (itemType != null) {
            where += " and report.item_type=:itemType";
            parameters.addValue("itemType", itemType.name());
        }
        if (position != null) {
            where += " and (report.reported_at,report.id)<(:reportedAt,:id)";
            parameters.addValue("reportedAt", Timestamp.from(position.reportedAt())).addValue("id", position.id());
        }
        return jdbc.query("select " + SUMMARY + " from tbl_musician_feed_content_report report" + where
                + " order by report.reported_at desc,report.id desc limit :limit", parameters,
                (row, index) -> summary(row));
    }

    public Optional<ReportRecord> find(UUID id, boolean lock) {
        return jdbc.query("select " + SUMMARY + ",report.viewer_user_id,report.evidence_json "
                        + "from tbl_musician_feed_content_report report where report.id=:id"
                        + (lock ? " for update" : ""), new MapSqlParameterSource("id", id),
                (row, index) -> new ReportRecord(summary(row), row.getObject("viewer_user_id", UUID.class),
                        evidence(row.getString("evidence_json")))).stream().findFirst();
    }

    public void transition(UUID id, long version, MusicianFeedReportStatus status,
            MusicianFeedReportDecision decision, UUID actor, String note, Instant now) {
        int updated = jdbc.update("""
                update tbl_musician_feed_content_report set version=version+1,status=:status,
                    review_decision=:decision,reviewed_by_user_id=:actor,reviewed_at=:now,resolution_note=:note
                where id=:id and version=:version
                """, new MapSqlParameterSource("id", id).addValue("version", version)
                .addValue("status", status.name()).addValue("decision", decision.name())
                .addValue("actor", actor).addValue("now", Timestamp.from(now)).addValue("note", note));
        if (updated != 1) throw new IllegalStateException("Locked moderation report changed unexpectedly");
    }

    private MusicianFeedReportSummary summary(ResultSet row) throws SQLException {
        return new MusicianFeedReportSummary(row.getObject("id", UUID.class), row.getLong("version"),
                MusicianFeedReportStatus.valueOf(row.getString("status")), row.getString("item_id"),
                row.getString("item_type"), row.getString("target_type"), row.getObject("target_id", UUID.class),
                row.getString("reason"), row.getTimestamp("reported_at").toInstant(), row.getString("title"),
                row.getString("author_display_name"));
    }

    private JsonNode evidence(String json) {
        try {
            JsonNode value = mapper.readTree(json);
            if (value == null || !value.isObject()) throw new IllegalArgumentException("Invalid evidence shape");
            return value;
        } catch (Exception invalid) {
            throw new IllegalStateException("Stored moderation evidence is invalid", invalid);
        }
    }

    public record ReportRecord(MusicianFeedReportSummary summary, UUID reporterUserId, JsonNode evidence) {
        public MusicianFeedModerationSubject subject() {
            return new MusicianFeedModerationSubject(summary.id(), summary.itemId(), summary.itemType(),
                    summary.targetType(), summary.targetId(), evidence);
        }
    }
}
