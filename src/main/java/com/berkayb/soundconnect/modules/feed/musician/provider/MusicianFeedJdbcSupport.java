package com.berkayb.soundconnect.modules.feed.musician.provider;

import com.berkayb.soundconnect.modules.feed.musician.api.*;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Time;
import java.time.*;
import java.util.List;
import java.util.UUID;

final class MusicianFeedJdbcSupport {
    private MusicianFeedJdbcSupport() { }

    private static final String BLOCKED_USERS = """
            (select blocked_id from tbl_user_block where blocker_id=:viewerId and unblocked_at is null
             union all select blocker_id from tbl_user_block where blocked_id=:viewerId and unblocked_at is null)
            """;

    // These aggregates belong to identified publications. The anonymous Overthinking
    // source nested inside a share uses the canonical Overthinking projection instead.
    static String withVisibleEngagement(String sql) {
        return sql.replace("/* VISIBLE_LIKER */", "likes.user_id not in " + BLOCKED_USERS)
                .replace("/* VISIBLE_COMMENT */", "comments.user_id not in " + BLOCKED_USERS + """
                        and (comments.parent_comment_id is null or exists
                          (select 1 from tbl_comment root where root.id=comments.parent_comment_id
                           and root.target_id=comments.target_id and root.target_type=comments.target_type
                           and root.user_id not in
                        """ + BLOCKED_USERS + "))");
    }

    static UUID uuid(ResultSet row, String column) throws SQLException {
        Object value = row.getObject(column);
        if (value == null) return null;
        return value instanceof UUID id ? id : UUID.fromString(value.toString());
    }

    static Instant instant(ResultSet row, String column) throws SQLException {
        Object value = row.getObject(column);
        if (value == null) return null;
        if (value instanceof Instant instant) return instant;
        if (value instanceof OffsetDateTime offset) return offset.toInstant();
        if (value instanceof LocalDateTime local) return local.toInstant(ZoneOffset.UTC);
        if (value instanceof Timestamp timestamp) return timestamp.toInstant();
        return Instant.parse(value.toString());
    }

    /** PostgreSQL JDBC does not bind {@link Instant} through setObject. */
    static Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }

    /**
     * Mirrors Hibernate's UTC-calendar LocalTime binding so raw JDBC can decode
     * legacy rows with the same storage origin used by the canonical event queries.
     */
    static LocalTime hibernateUtcStorageTime(LocalTime value) {
        return Instant.ofEpochMilli(Time.valueOf(value).getTime())
                .atZone(ZoneOffset.UTC).toLocalTime();
    }

    static LocalTime normalizedTime(ResultSet row, String column) throws SQLException {
        Number seconds = (Number) row.getObject(column);
        return seconds == null ? null : LocalTime.ofSecondOfDay(Math.floorMod(seconds.longValue(), 86_400));
    }

    static MusicianFeedItemResponse.Author author(ResultSet row) throws SQLException {
        return new MusicianFeedItemResponse.Author(uuid(row, "author_user_id"),
                uuid(row, "author_profile_id"), row.getString("author_profile_type"),
                row.getString("author_username"), row.getString("author_display_name"),
                row.getString("author_avatar_url"), row.getBoolean("followed_by_viewer"));
    }

    static MusicianFeedItemResponse.Reason publicationReason(
            MusicianFeedItemResponse.Author author,
            MusicianFeedReasonCode discoveryReason
    ) {
        return new MusicianFeedItemResponse.Reason(
                author.followedByViewer() ? MusicianFeedReasonCode.FOLLOWING_PUBLICATION : discoveryReason,
                author.followedByViewer() ? List.of(author) : List.of(), 0);
    }

    static MusicianFeedItemResponse.Engagement engagement(
            ResultSet row,
            String targetType,
            UUID targetId
    ) throws SQLException {
        return new MusicianFeedItemResponse.Engagement(targetType, targetId,
                row.getLong("like_count"), row.getLong("comment_count"),
                row.getBoolean("liked_by_me"), true, true);
    }

    static List<MusicianFeedFeedbackAction> standardFeedback() {
        // Author muting is deliberately exposed only through the dedicated
        // author endpoint; it is not an item-feedback capability/action.
        return List.of(MusicianFeedFeedbackAction.HIDE, MusicianFeedFeedbackAction.SHOW_LESS,
                MusicianFeedFeedbackAction.REPORT);
    }
}
