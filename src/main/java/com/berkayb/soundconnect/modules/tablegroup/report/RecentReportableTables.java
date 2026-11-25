package com.berkayb.soundconnect.modules.tablegroup.report;

import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.response.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class RecentReportableTables {
    private final NamedParameterJdbcTemplate jdbc;
    static final Duration WINDOW = Duration.ofHours(48);

    // The immutable notification event is written in the same transaction as a
    // leave/kick and survives outbox dispatch, membership pruning and reapplication.
    private static final String RECENT = """
        with member_tables as (
            select id from tbl_table_group where owner_id=:actor
            union
            select table_group_id from tbl_table_group_participants where user_id=:actor and status='ACCEPTED'
        ), ended as (
            select p.table_group_id, p.status as reason, p.joined_at as ended_at
            from tbl_table_group_participants p
            where p.user_id=:actor and p.status in ('KICKED','LEFT')
              and p.joined_at>:cutoff and p.joined_at<=:at
            union all
            select case when pg_input_is_valid(e.payload->>'tableGroupId','uuid')
                        then cast(e.payload->>'tableGroupId' as uuid) end,
                   case when e.notification_type='TABLE_REMOVED' then 'KICKED' else 'LEFT' end,
                   e.occurred_at
            from tbl_table_notification_event e
            where e.occurred_at>:cutoff and e.occurred_at<=:at and e.payload->>'module'='TABLE' and (
                (e.notification_type='TABLE_REMOVED' and e.payload->>'action'='PARTICIPANT_REMOVED' and e.recipient_id=:actor)
                or (e.notification_type='TABLE_PARTICIPANT_LEFT' and e.payload->>'action'='PARTICIPANT_LEFT'
                    and e.payload->>'leaverId'=cast(:actor as text)))
            union all
            select t.id, case when t.status='CANCELLED' then 'CLOSED' else 'EXPIRED' end,
                   case when t.status='CANCELLED' then t.report_closed_at else t.expires_at end
            from tbl_table_group t join member_tables m on m.id=t.id
            where (t.status='CANCELLED' and t.report_closed_at>:cutoff and t.report_closed_at<=:at)
               or (t.status in ('ACTIVE','INACTIVE') and t.expires_at>:cutoff and t.expires_at<=:at)
        ), latest as (
            select distinct on (table_group_id) table_group_id, reason, ended_at
            from ended order by table_group_id, ended_at desc, reason
        )
        """;
    private static final String SOURCE = """
        from latest e join tbl_table_group t on t.id=e.table_group_id
        join tbl_city c on c.id=t.city_id left join tbl_district d on d.id=t.district_id
        where not (t.status='ACTIVE' and t.expires_at>:at and exists(select 1 from member_tables m where m.id=t.id))
        """;
    private static final String COLUMNS = """
        select t.id, case when t.report_removed_at is null then t.description
                          else 'Bu masa yayından kaldırıldı.' end as table_description,
               t.venue_name, c.name as city_name, d.name as district_name, t.meeting_at, e.reason, e.ended_at
        """;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<Row> list(UUID actor, int page, int size) {
        if (page < 0 || page > 1000 || size < 1 || size > 50) {
            throw new SoundConnectException(ErrorType.TABLE_GROUP_PAGE_REQUEST_INVALID);
        }
        var pageable = PageRequest.of(page, size);
        var params = parameters(actor, Instant.now()).addValue("size", size).addValue("offset", pageable.getOffset());
        var rows = jdbc.query(RECENT + COLUMNS + SOURCE + " order by e.ended_at desc,t.id desc limit :size offset :offset",
                params, RecentReportableTables::row);
        return PageResponse.from(PageableExecutionUtils.getPage(rows, pageable,
                () -> jdbc.queryForObject(RECENT + " select count(*) " + SOURCE, params, Long.class)));
    }

    Optional<Row> find(UUID actor, UUID table, Instant now) {
        return jdbc.query(RECENT + COLUMNS + SOURCE + " and t.id=:table",
                parameters(actor, now).addValue("table", table), RecentReportableTables::row).stream().findFirst();
    }

    private static MapSqlParameterSource parameters(UUID actor, Instant now) {
        return new MapSqlParameterSource("actor", actor).addValue("at", Timestamp.from(now))
                .addValue("cutoff", Timestamp.from(now.minus(WINDOW)));
    }

    private static Row row(ResultSet rs, int index) throws SQLException {
        Instant end = rs.getTimestamp("ended_at").toInstant();
        return new Row(rs.getObject("id", UUID.class), rs.getString("table_description"), rs.getString("venue_name"),
                rs.getString("city_name"), rs.getString("district_name"), rs.getTimestamp("meeting_at").toInstant(),
                rs.getString("reason"), end, end.plus(WINDOW));
    }

    public record Row(UUID tableGroupId, String tableDescription, String venueName, String cityName,
                      String districtName, Instant meetingAt, String endReason, Instant endedAt, Instant reportUntil) {}
}
