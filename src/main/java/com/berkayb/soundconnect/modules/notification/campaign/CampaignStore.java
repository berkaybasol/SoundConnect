package com.berkayb.soundconnect.modules.notification.campaign;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import com.berkayb.soundconnect.shared.exception.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import static com.berkayb.soundconnect.modules.notification.campaign.CampaignContract.*;

@Repository
@RequiredArgsConstructor
public class CampaignStore {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public record Row(UUID id, UUID createdBy, long version, Write definition, Status status, Instant next,
                      long occurrences, long recipients, long notifications, long skipped,
                      Instant created, Instant updated) { }

    public String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("Invalid campaign definition", failure);
        }
    }

    public Write definition(String value) {
        try {
            return mapper.readValue(value, Write.class);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Stored campaign definition invalid", failure);
        }
    }

    private final RowMapper<Row> row = (rs, index) -> new Row(
            rs.getObject("id", UUID.class), rs.getObject("created_by", UUID.class), rs.getLong("version"),
            definition(rs.getString("definition")), Status.valueOf(rs.getString("status")), instant(rs, "next_run_at"),
            rs.getLong("occurrences"), rs.getLong("recipients"), rs.getLong("notifications"), rs.getLong("skipped"),
            instant(rs, "created_at"), instant(rs, "updated_at"));

    // Repository exception translation may wrap this object in CGLIB. Cross-bean
    // access must dispatch a method on the target, never read fields on its proxy.
    public NamedParameterJdbcTemplate jdbc() {
        return jdbc;
    }

    public RowMapper<Row> rowMapper() {
        return row;
    }

    public Row get(UUID id, boolean lock) {
        if (id == null) {
            throw missing();
        }
        return jdbc.query("select * from tbl_notification_campaign where id=:id" + (lock ? " for update" : ""),
                Map.of("id", id), row).stream().findFirst().orElseThrow(CampaignStore::missing);
    }

    /** Caller holds the campaign row lock; previously delivered receipts and inbox rows stay intact. */
    public void completeRunningOccurrence(UUID campaignId, Instant now) {
        jdbc.update(
                "update tbl_notification_campaign_occurrence set status='COMPLETED',completed_at=:now where campaign_id=:id and status='RUNNING'",
                Map.of("id", campaignId, "now", Timestamp.from(now)));
    }

    static Instant instant(ResultSet rs, String name) throws SQLException {
        var value = rs.getTimestamp(name);
        return value == null ? null : value.toInstant();
    }

    static SoundConnectException missing() {
        return new SoundConnectException(ErrorType.NOTIFICATION_NOT_FOUND);
    }

    static SoundConnectException conflict() {
        return new SoundConnectException(ErrorType.NOTIFICATION_CAMPAIGN_CONFLICT);
    }
}
