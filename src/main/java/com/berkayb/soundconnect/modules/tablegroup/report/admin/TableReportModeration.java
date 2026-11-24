package com.berkayb.soundconnect.modules.tablegroup.report.admin;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.admin.security.AdminPanelReadAccess;
import com.berkayb.soundconnect.modules.role.enums.PermissionEnum;
import com.berkayb.soundconnect.modules.tablegroup.chat.service.TableGroupChatService;
import com.berkayb.soundconnect.modules.tablegroup.report.TableGroupReportRequest.Kind;
import com.berkayb.soundconnect.modules.tablegroup.service.TableGroupService;
import com.berkayb.soundconnect.modules.user.support.AccountDeliveryFence;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.response.PageResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.RequiredArgsConstructor;
import org.hibernate.validator.constraints.CodePointLength;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TableReportModeration {
    public enum Status { OPEN, RESOLVED }
    public enum Decision { DISMISS, CONFIRM_VIOLATION, REMOVE_MESSAGE, REMOVE_TABLE }

    public record Review(@NotNull Decision decision, @NotBlank @CodePointLength(min=5,max=500) String resolutionNote,
                         @NotNull @PositiveOrZero Long expectedVersion) { }
    public record Summary(UUID id, UUID tableGroupId, String tableDescription, String kind, String reason, String status,
                          long version, String reporterUsername, Instant reportedAt) { }
    public record Detail(UUID id, UUID tableGroupId, String tableDescription, String kind, String reason, String status,
                         long version, String reporterUsername, Instant reportedAt, UUID reporterId, String description,
                         String venueName, String cityName, String districtName, Instant meetingAt, String ownerUsername,
                         UUID messageId, String messageText, String senderUsername, Instant messageSentAt,
                         String membershipEndReason, Instant membershipEndedAt, boolean tableActive, boolean tableRemoved,
                         boolean messageRemovable, String decision, String resolutionNote, String reviewerUsername,
                         Instant reviewedAt) { }
    private record ReportRow(Detail detail, UUID reviewerId) { }

    private static final String DETAIL = """
            select r.*, t.status='ACTIVE' and t.expires_at>clock_timestamp() as table_active,
                   t.report_removed_at is not null as table_removed,
                   m.id is not null and m.deleted_at is null and m.message_type='TEXT' as message_present
            from tbl_table_group_report r
            left join tbl_table_group t on t.id=r.table_group_id
            left join tbl_table_group_message m on m.id=r.message_id and m.table_group_id=r.table_group_id
            where r.id=:id
            """;
    private final NamedParameterJdbcTemplate jdbc;
    private final AdminPanelReadAccess access;
    private final AccountDeliveryFence accounts;
    private final TableGroupService tables;
    private final TableGroupChatService chats;

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public PageResponse<Summary> list(UserDetailsImpl actor, Status status, Kind kind, int page, int size) {
        requireAccess(actor);
        if (status == null || page < 0 || page > 1000 || size < 1 || size > 50) throw invalid();
        var pageable = PageRequest.of(page, size);
        String filter = " from tbl_table_group_report where status=:status";
        var params = new MapSqlParameterSource("status", status.name())
                .addValue("size", size).addValue("offset", pageable.getOffset());
        if (kind != null) {
            filter += " and kind=:kind";
            params.addValue("kind", kind.name());
        }
        var rows = jdbc.query("""
                select id,table_group_id,table_description,kind,reason,status,version,reporter_username,reported_at
                """ + filter + " order by reported_at desc,id desc limit :size offset :offset", params,
                (rs, i) -> new Summary(rs.getObject("id", UUID.class), rs.getObject("table_group_id", UUID.class),
                        rs.getString("table_description"), rs.getString("kind"), rs.getString("reason"),
                        rs.getString("status"), rs.getLong("version"), rs.getString("reporter_username"),
                        at(rs,"reported_at")));
        String count = "select count(*)" + filter;
        return PageResponse.from(PageableExecutionUtils.getPage(rows, pageable,
                () -> jdbc.queryForObject(count, params, Long.class)));
    }

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public Detail detail(UserDetailsImpl actor, UUID id) {
        requireAccess(actor);
        return read(id, false).detail();
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Detail review(UserDetailsImpl actor, UUID id, Review request) {
        requireAccess(actor);
        if (request == null || request.decision() == null || request.expectedVersion() == null
                || request.expectedVersion() < 0) throw invalid();
        String note = request.resolutionNote() == null ? "" : request.resolutionNote().strip();
        int length = note.codePointCount(0, note.length());
        if (length < 5 || length > 500) throw invalid();
        accounts.requireActive(List.of(actor.getId()));
        Decision decision = request.decision();
        if (decision == Decision.REMOVE_MESSAGE || decision == Decision.REMOVE_TABLE) {
            // User reports and table lifecycle writes also acquire the aggregate before evidence rows.
            jdbc.queryForList("""
                    select t.id from tbl_table_group t join tbl_table_group_report r on r.table_group_id=t.id
                    where r.id=:id for update of t
                    """, Map.of("id", id), UUID.class);
        }
        ReportRow row = read(id, true);
        Detail report = row.detail();
        requireAccess(actor);
        if (!Status.OPEN.name().equals(report.status())) {
            if (actor.getId().equals(row.reviewerId()) && request.expectedVersion() == report.version() - 1
                    && decision.name().equals(report.decision()) && note.equals(report.resolutionNote())) {
                return report;
            }
            throw changed();
        }
        if (report.version() != request.expectedVersion()) throw changed();
        switch (decision) {
            case REMOVE_MESSAGE -> {
                if (!report.messageRemovable()) throw changed();
                chats.removeReportedMessage(report.tableGroupId(), report.messageId());
            }
            case REMOVE_TABLE -> {
                if (!report.tableActive()) throw changed();
                redactAndClose(report.tableGroupId());
            }
            case DISMISS, CONFIRM_VIOLATION -> { /* resolution only */ }
        }
        jdbc.update("""
                update tbl_table_group_report set status='RESOLVED',decision=:decision,resolution_note=:note,
                    reviewer_id=:actor,reviewer_username=(select user_name from tbl_user where id=:actor),
                    reviewed_at=clock_timestamp(),version=version+1 where id=:id
                """, Map.of("decision",decision.name(),"note",note,"actor",actor.getId(),"id",id));
        return read(id, false).detail();
    }

    private void redactAndClose(UUID tableId) {
        // Write the new description to the aggregate BEFORE the normal cancellation trigger
        // snapshots it for public profile cards. Both writes belong to the review transaction.
        var params = Map.of("tableId", tableId);
        int changed = jdbc.update("""
                update tbl_table_group set description='Bu masa yayından kaldırıldı.',report_removed_at=clock_timestamp()
                where id=:tableId and status='ACTIVE' and expires_at>clock_timestamp()
                """, params);
        if (changed != 1 || !Boolean.TRUE.equals(jdbc.queryForObject("""
                select expires_at>clock_timestamp() from tbl_table_group where id=:tableId
                """, params, Boolean.class))) throw changed();
        tables.closeForModeration(tableId);
    }

    private ReportRow read(UUID id, boolean lock) {
        return jdbc.query(DETAIL + (lock ? " for update of r" : ""), Map.of("id", id),
                (rs, row) -> {
                    boolean active = rs.getBoolean("table_active");
                    var detail = new Detail(id,rs.getObject("table_group_id",UUID.class), rs.getString("table_description"),
                            rs.getString("kind"),rs.getString("reason"),rs.getString("status"),rs.getLong("version"),
                            rs.getString("reporter_username"),at(rs,"reported_at"),rs.getObject("reporter_id",UUID.class),
                            rs.getString("description"),rs.getString("venue_name"),rs.getString("city_name"),
                            rs.getString("district_name"),at(rs,"meeting_at"),rs.getString("owner_username"),
                            rs.getObject("message_id",UUID.class),rs.getString("message_text"),rs.getString("sender_username"),
                            at(rs,"message_sent_at"),rs.getString("membership_end_reason"),at(rs,"membership_ended_at"),
                            active,rs.getBoolean("table_removed"),active && rs.getBoolean("message_present")
                                    && Kind.MESSAGE.name().equals(rs.getString("kind")),rs.getString("decision"),
                            rs.getString("resolution_note"),rs.getString("reviewer_username"),at(rs,"reviewed_at"));
                    return new ReportRow(detail, rs.getObject("reviewer_id", UUID.class));
                }).stream().findFirst().orElseThrow(() -> new SoundConnectException(ErrorType.TABLE_GROUP_REPORT_NOT_FOUND));
    }

    private void requireAccess(UserDetailsImpl actor) {
        access.require(actor, PermissionEnum.MANAGE_TABLE_GROUP_REPORTS);
    }

    private static Instant at(ResultSet row, String column) throws SQLException {
        Timestamp value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static SoundConnectException invalid() { return new SoundConnectException(ErrorType.VALIDATION_ERROR); }
    private static SoundConnectException changed() { return new SoundConnectException(ErrorType.TABLE_GROUP_REPORT_STATE_CHANGED); }
}
