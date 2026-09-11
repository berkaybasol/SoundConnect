package com.berkayb.soundconnect.modules.feed.musician.feedback;

import com.berkayb.soundconnect.modules.collab.dto.request.CollabReportCreateRequest;
import com.berkayb.soundconnect.modules.collab.enums.CollabReportReason;
import com.berkayb.soundconnect.modules.collab.service.CollabService;
import com.berkayb.soundconnect.modules.feed.musician.delivery.MusicianFeedDeliveredItem;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Component
public class MusicianFeedReportDispatcher {
    private final NamedParameterJdbcTemplate jdbc;
    private final CollabService collabs;

    public MusicianFeedReportDispatcher(NamedParameterJdbcTemplate jdbc, CollabService collabs) {
        this.jdbc = jdbc;
        this.collabs = collabs;
    }

    public void report(UUID viewerId, MusicianFeedDeliveredItem delivery, String reason, Instant now) {
        String details = reason == null ? "Musician feed report" : reason;
        if ("COLLAB".equals(delivery.targetType())) {
            // Preserve the Collab aggregate's current visibility, self-report,
            // idempotency and immutable listing-evidence semantics.
            collabs.report(viewerId, delivery.targetId(), new CollabReportCreateRequest(
                    delivery.deliveryId(), CollabReportReason.OTHER, details));
            return;
        }
        var parameters = new MapSqlParameterSource().addValue("id", UUID.randomUUID())
                .addValue("viewerId", viewerId).addValue("deliveryId", delivery.deliveryId())
                .addValue("itemId", delivery.itemId()).addValue("itemType", delivery.itemType().name())
                .addValue("targetType", delivery.targetType()).addValue("targetId", delivery.targetId())
                .addValue("reason", reason).addValue("evidenceJson", delivery.evidenceJson())
                .addValue("reportedAt", Timestamp.from(now));
        int inserted = jdbc.update("""
                insert into tbl_musician_feed_content_report(
                    id,viewer_user_id,delivery_id,item_id,item_type,target_type,target_id,reason,
                    evidence_json,status,reported_at)
                values(:id,:viewerId,:deliveryId,:itemId,:itemType,:targetType,:targetId,:reason,
                    cast(:evidenceJson as jsonb),'NEW',:reportedAt)
                on conflict(viewer_user_id,delivery_id) do nothing
                """, parameters);
        if (inserted == 0) {
            List<String> existing = jdbc.queryForList("""
                    select coalesce(reason,'') from tbl_musician_feed_content_report
                    where viewer_user_id=:viewerId and delivery_id=:deliveryId
                    """, parameters, String.class);
            if (existing.size() != 1 || !Objects.equals(existing.getFirst(), reason == null ? "" : reason)) {
                throw new SoundConnectException(ErrorType.BAD_REQUEST);
            }
        }
    }
}
