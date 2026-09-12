package com.berkayb.soundconnect.modules.like.repository;

import com.berkayb.soundconnect.modules.engagement.enums.EngagementTargetType;
import com.berkayb.soundconnect.modules.like.service.LikeUsersCursor;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class LikeUsersReadRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public List<Row> page(EngagementTargetType type, UUID targetId, LikeUsersCursor after, int limit) {
        if (limit < 1 || limit > 51) throw new IllegalArgumentException("Like users page exceeds its bound");
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("type", type.name()).addValue("targetId", targetId).addValue("limit", limit);
        String seek = "";
        if (after != null) {
            parameters.addValue("afterId", after.likeId());
            if (after.createdAt() == null) {
                // PostgreSQL's DESC ordering puts historical null timestamps first.
                seek = "and ((likes.created_at is null and likes.id<:afterId) or likes.created_at is not null)";
            } else {
                parameters.addValue("afterTime", Timestamp.valueOf(after.createdAt()));
                seek = "and (likes.created_at,likes.id)<(:afterTime,:afterId)";
            }
        }
        return jdbc.query("""
                select likes.id,likes.user_id,likes.created_at
                from tbl_like likes join tbl_user account on account.id=likes.user_id
                where likes.target_type=:type and likes.target_id=:targetId
                  and account.status='ACTIVE' and account.email_verified and account.erased_at is null
                """ + seek + " order by likes.created_at desc,likes.id desc limit :limit", parameters,
                (row, index) -> {
                    Timestamp created = row.getTimestamp("created_at");
                    return new Row(row.getObject("id", UUID.class), row.getObject("user_id", UUID.class),
                            created == null ? null : created.toLocalDateTime());
                });
    }

    public record Row(UUID id, UUID userId, LocalDateTime createdAt) { }
}
