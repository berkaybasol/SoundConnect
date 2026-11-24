package com.berkayb.soundconnect.modules.tablegroup.report;

import com.berkayb.soundconnect.modules.tablegroup.abuse.TableGroupRateLimitGuard;
import com.berkayb.soundconnect.modules.user.support.AccountDeliveryFence;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static com.berkayb.soundconnect.modules.tablegroup.report.TableGroupReportRequest.Kind.*;

@Service
@RequiredArgsConstructor
public class TableGroupReportService {
    private final NamedParameterJdbcTemplate jdbc;
    private final RecentReportableTables recent;
    private final AccountDeliveryFence accounts;
    private final TableGroupRateLimitGuard limits;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Receipt create(UUID actor, UUID table, TableGroupReportRequest input) {
        if (input == null) throw new SoundConnectException(ErrorType.VALIDATION_ERROR);
        var request = input.normalized();
        var params = new MapSqlParameterSource("actor", actor).addValue("table", table)
                .addValue("key", request.requestId()).addValue("msg", request.messageId())
                .addValue("kind", request.kind().name()).addValue("reason", request.reason().name())
                .addValue("note", request.description());

        jdbc.queryForObject("select 1 from pg_advisory_xact_lock(hashtextextended(:scope,0))",
                Map.of("scope", "table-report:" + actor + ":" + request.requestId()), Integer.class);
        // Account erasure takes the user lock before any shared table. Lock the
        // snapshot subjects in that order so an erased username cannot reappear.
        Map<UUID, String> names = lockIdentities(params);
        accounts.requireActive(List.of(actor));
        Receipt replay = existing(params, request, table);
        if (replay != null) return replay;

        if (jdbc.queryForList("select id from tbl_table_group where id=:table for update", params, UUID.class).isEmpty()) {
            throw new SoundConnectException(ErrorType.TABLE_GROUP_NOT_FOUND);
        }
        Instant now = Instant.now();
        params.addValue("at", Timestamp.from(now));
        var state = jdbc.queryForObject("""
                select t.owner_id, t.status='ACTIVE' and t.expires_at>:at and t.report_removed_at is null as active,
                       t.owner_id=:actor or exists (select 1 from tbl_table_group_participants p
                           where p.table_group_id=t.id and p.user_id=:actor and p.status='ACCEPTED') as member
                from tbl_table_group t where t.id=:table
                """, params, (rs, row) -> new Access(rs.getObject("owner_id", UUID.class),
                rs.getBoolean("active"), rs.getBoolean("member")));
        boolean liveMember = state.active() && state.member();
        RecentReportableTables.Row ended = null;
        if (request.kind() == INCIDENT && !liveMember) {
            ended = recent.find(actor, table, now).orElseThrow(TableGroupReportService::unavailable);
        } else if (!state.active() || (request.kind() == MESSAGE && !liveMember)) {
            throw unavailable();
        }
        Message message = request.kind() == MESSAGE ? selectedMessage(params) : null;
        limits.checkReport(actor);

        UUID id = UUID.randomUUID();
        UUID owner = state.ownerId();
        UUID sender = message == null ? null : message.sender();
        params.addValue("id", id).addValue("reporterName", names.get(actor))
                .addValue("owner", names.containsKey(owner) ? owner : null).addValue("ownerName", names.get(owner))
                .addValue("sender", names.containsKey(sender) ? sender : null).addValue("senderName", names.get(sender))
                .addValue("sent", message == null ? null : message.sentAt()).addValue("text", message == null ? null : message.text())
                .addValue("endReason", ended == null ? null : ended.endReason())
                .addValue("endTime", ended == null ? null : Timestamp.from(ended.endedAt()));
        return jdbc.queryForObject("""
                insert into tbl_table_group_report (
                    id,request_id,reporter_id,reporter_username,table_group_id,kind,reason,description,reported_at,
                    table_description,venue_name,city_name,district_name,meeting_at,owner_id,owner_username,
                    message_id,message_text,sender_id,sender_username,message_sent_at,membership_end_reason,membership_ended_at)
                select :id,:key,:actor,:reporterName,t.id,:kind,:reason,:note,:at,
                       t.description,t.venue_name,c.name,d.name,t.meeting_at,:owner,:ownerName,
                       :msg,:text,:sender,:senderName,:sent,:endReason,:endTime
                from tbl_table_group t join tbl_city c on c.id=t.city_id left join tbl_district d on d.id=t.district_id
                where t.id=:table returning id,reported_at
                """, params, (rs, row) -> new Receipt(rs.getObject("id", UUID.class), rs.getTimestamp("reported_at").toInstant()));
    }

    private Map<UUID, String> lockIdentities(MapSqlParameterSource params) {
        return jdbc.query("""
                select u.id, u.user_name, u.erased_at from tbl_user u where u.id in (
                    select cast(:actor as uuid) union select owner_id from tbl_table_group where id=:table
                    union select sender_id from tbl_table_group_message where id=:msg and table_group_id=:table
                ) order by u.id for share
                """, params, rs -> {
            Map<UUID, String> names = new HashMap<>();
            while (rs.next()) {
                if (rs.getTimestamp("erased_at") == null) names.put(rs.getObject("id", UUID.class), rs.getString("user_name"));
            }
            return names;
        });
    }

    private Receipt existing(MapSqlParameterSource params, TableGroupReportRequest request, UUID table) {
        return jdbc.query("""
                select id,table_group_id,kind,reason,description,message_id,reported_at
                from tbl_table_group_report where reporter_id=:actor and request_id=:key
                """, params, rs -> {
            if (!rs.next()) return null;
            if (!table.equals(rs.getObject("table_group_id", UUID.class)) || !request.kind().name().equals(rs.getString("kind"))
                    || !request.reason().name().equals(rs.getString("reason")) || !Objects.equals(request.description(), rs.getString("description"))
                    || !Objects.equals(request.messageId(), rs.getObject("message_id", UUID.class))) {
                throw new SoundConnectException(ErrorType.TABLE_GROUP_REPORT_REQUEST_CONFLICT);
            }
            return new Receipt(rs.getObject("id", UUID.class), rs.getTimestamp("reported_at").toInstant());
        });
    }

    private Message selectedMessage(MapSqlParameterSource params) {
        return jdbc.query("""
                select sender_id, content, created_at at time zone 'UTC' as sent
                from tbl_table_group_message where id=:msg and table_group_id=:table
                    and sender_id<>:actor and message_type='TEXT' and deleted_at is null
                """, params, (rs, row) -> new Message(rs.getObject("sender_id", UUID.class),
                rs.getString("content"), rs.getTimestamp("sent"))).stream().findFirst().orElseThrow(TableGroupReportService::unavailable);
    }

    private static SoundConnectException unavailable() {
        return new SoundConnectException(ErrorType.TABLE_GROUP_REPORT_TARGET_UNAVAILABLE);
    }

    private record Access(UUID ownerId, boolean active, boolean member) {}
    private record Message(UUID sender, String text, Timestamp sentAt) {}
    public record Receipt(UUID id, Instant reportedAt) {}
}
