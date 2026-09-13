package com.berkayb.soundconnect.modules.feed.musician.moderation;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Repository
public class MusicianFeedRestrictionRepository {
    private static final int BATCH_SIZE = 500;
    private final NamedParameterJdbcTemplate jdbc;

    public MusicianFeedRestrictionRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<State> byReport(UUID reportId) {
        if (reportId == null) return Optional.empty();
        return jdbc.query("select scope_key,active from tbl_musician_feed_restriction where report_id=:id",
                new MapSqlParameterSource("id", reportId), (row, index) ->
                        new State(row.getString("scope_key"), row.getBoolean("active"))).stream().findFirst();
    }

    public void apply(UUID reportId, String scope, UUID actor, Instant now) {
        jdbc.update("""
                insert into tbl_musician_feed_restriction(report_id,scope_key,active,orphaned,applied_by_user_id,applied_at,updated_at)
                values(:report,:scope,true,not exists(select 1 from tbl_musician_feed_content_report where id=:report),:actor,:now,:now)
                on conflict(report_id) do update set active=true,orphaned=excluded.orphaned,
                    applied_by_user_id=:actor,applied_at=:now,updated_at=:now
                where not tbl_musician_feed_restriction.active
                """, new MapSqlParameterSource("report", reportId).addValue("scope", scope)
                .addValue("actor", actor).addValue("now", Timestamp.from(now)));
    }

    public void restore(UUID reportId, Instant now) {
        jdbc.update("update tbl_musician_feed_restriction set active=false,updated_at=:now where report_id=:report and active",
                new MapSqlParameterSource("report", reportId).addValue("now", Timestamp.from(now)));
    }

    /** The returned set and index work are bounded by the requested scopes, not restriction history. */
    public Set<String> activeScopes(Collection<String> requested) {
        if (requested.size() > 30_000) throw new IllegalArgumentException("Too many feed restriction scopes");
        List<String> scopes = requested.stream().filter(Objects::nonNull).distinct().toList();
        Set<String> result = new HashSet<>();
        for (int start = 0; start < scopes.size(); start += BATCH_SIZE) {
            var parameters = new MapSqlParameterSource();
            StringJoiner values = new StringJoiner(",");
            for (int index = start; index < Math.min(start + BATCH_SIZE, scopes.size()); index++) {
                values.add("(:scope" + index + ")");
                parameters.addValue("scope" + index, scopes.get(index));
            }
            jdbc.query("""
                    select requested.scope_key from (values %s) requested(scope_key)
                    join lateral (
                        select 1 from tbl_musician_feed_restriction restriction
                        where restriction.scope_key=requested.scope_key and restriction.active
                        order by restriction.report_id limit 1
                    ) blocked on true
                    """.formatted(values), parameters, row -> { result.add(row.getString("scope_key")); });
        }
        return Set.copyOf(result);
    }

    public record State(String scopeKey, boolean active) { }
}
