package com.berkayb.soundconnect.modules.contentreport;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.admin.security.AdminPanelReadAccess;
import com.berkayb.soundconnect.modules.role.enums.PermissionEnum;
import com.berkayb.soundconnect.modules.user.support.AccountDeliveryFence;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.response.PageResponse;
import com.berkayb.soundconnect.shared.util.UsernameUtils;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ContentReportModeration {
    public enum Status { OPEN, RESOLVED }
    public enum Decision { DISMISS, CONFIRM_VIOLATION, REMOVE_CONTENT }

    public record Review(@NotNull Decision decision, @NotBlank @CodePointLength(min=5,max=500) String resolutionNote,
                         @NotNull @PositiveOrZero Long expectedVersion) {}
    public record Summary(UUID id, ContentReportKind kind, String reason, Status status, long version, Instant reportedAt,
                          String reporterUsername, String targetLabel) {}
    public record Detail(UUID id, ContentReportKind kind, String reason, Status status, long version, Instant reportedAt,
                         String reporterUsername, String targetLabel, String evidenceText, String parentText,
                         String description, UUID profileUserId, boolean removable, String decision,
                         String resolutionNote, String reviewerUsername, Instant reviewedAt) {}

    private static final String FROM = " from tbl_content_report r left join tbl_user u on u.id=r.reporter_id";
    private final NamedParameterJdbcTemplate jdbc;
    private final AdminPanelReadAccess access;
    private final AccountDeliveryFence accounts;
    private final ContentReportTargets targets;

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public PageResponse<Summary> list(UserDetailsImpl actor, Status status, ContentReportKind kind, int page, int size) {
        access.require(actor, PermissionEnum.MANAGE_CONTENT_REPORTS);
        if (status == null || page < 0 || page > 1000 || size < 1 || size > 50) throw invalid();
        var pageable = PageRequest.of(page,size);
        var params = new MapSqlParameterSource("status",status.name()).addValue("size",size)
                .addValue("offset",pageable.getOffset());
        String where = " where r.status=:status";
        if (kind != null) {
            where += " and r.kind=:kind";
            params.addValue("kind",kind.name());
        }
        String countSql = "select count(*) from tbl_content_report r" + where;
        List<Summary> rows = jdbc.query("""
                select r.id,r.kind,r.reason,r.status,r.version,r.reported_at,r.target_label,u.user_name as reporter_name
                """ + FROM + where + " order by r.reported_at desc,r.id desc limit :size offset :offset",params,
                (row,n) -> summary(row));
        return PageResponse.from(PageableExecutionUtils.getPage(rows,pageable,
                () -> jdbc.queryForObject(countSql,params,Long.class)));
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Detail detail(UserDetailsImpl actor, UUID id) {
        access.require(actor, PermissionEnum.MANAGE_CONTENT_REPORTS);
        return detail(load(id,false));
    }

    @Transactional
    public Detail review(UserDetailsImpl actor, UUID id, Review request) {
        if (actor == null) throw new SoundConnectException(ErrorType.UNAUTHORIZED);
        accounts.requireActive(List.of(actor.getId()));
        access.require(actor, PermissionEnum.MANAGE_CONTENT_REPORTS);
        if (request == null || request.decision()==null || request.expectedVersion()==null || request.expectedVersion()<0) throw invalid();
        String note = UsernameUtils.stripBoundaryWhitespace(request.resolutionNote());
        if (note == null || note.indexOf(0) >= 0 || note.codePointCount(0,note.length()) < 5 ||
                note.codePointCount(0,note.length()) > 500) throw invalid();
        Row report = load(id,true);
        if (report.summary().status()==Status.RESOLVED) {
            if (Objects.equals(actor.getId(), report.reviewerId())
                    && request.expectedVersion() == report.summary().version() - 1
                    && request.decision().name().equals(report.decision()) && note.equals(report.note())) {
                return detail(report);
            }
            throw changed();
        }
        if (report.summary().version()!=request.expectedVersion()) throw changed();
        if (request.decision()==Decision.REMOVE_CONTENT) {
            if (report.summary().kind().isProfile()) throw invalid();
            targets.remove(report.summary().kind(), report.targetId(), report.evidence());
        }
        jdbc.update("""
                update tbl_content_report set status='RESOLVED', decision=:decision, resolution_note=:note,
                    reviewer_id=:actor, reviewed_at=clock_timestamp(), version=version+1 where id=:id
                """, Map.of("id",id,"actor",actor.getId(),"decision",request.decision().name(),"note",note));
        return detail(load(id,false));
    }

    private record Row(Summary summary, UUID targetId, ContentReportEvidence evidence, String description,
                       String decision,String note,UUID reviewerId,String reviewerName,Instant reviewed) {}

    private Row load(UUID id, boolean lock) {
        return jdbc.query("""
                select r.*,u.user_name as reporter_name, reviewer.user_name as reviewer_name
                """ + FROM + " left join tbl_user reviewer on reviewer.id=r.reviewer_id where r.id=:id" +
                (lock ? " for update of r" : ""),Map.of("id",id),(rs,n) -> {
            Summary item = summary(rs);
            return new Row(item,rs.getObject("target_id",UUID.class),
                    new ContentReportEvidence(item.targetLabel(),rs.getString("evidence_text"),rs.getString("parent_text")),
                    rs.getString("description"),rs.getString("decision"),rs.getString("resolution_note"),
                    rs.getObject("reviewer_id",UUID.class),rs.getString("reviewer_name"),instant(rs,"reviewed_at"));
        }).stream().findFirst().orElseThrow(() -> new SoundConnectException(ErrorType.CONTENT_REPORT_NOT_FOUND));
    }

    private Detail detail(Row row) {
        var s = row.summary();
        return new Detail(s.id(),s.kind(),s.reason(),s.status(),s.version(),s.reportedAt(),s.reporterUsername(),
                s.targetLabel(),row.evidence().evidenceText(),row.evidence().parentText(),row.description(),
                s.kind().isProfile() ? row.targetId() : null,
                s.status()==Status.OPEN && targets.removable(s.kind(),row.targetId(),row.evidence()),row.decision(),row.note(),
                row.reviewerName(),row.reviewed());
    }

    private static Summary summary(ResultSet rs) throws SQLException {
        return new Summary(rs.getObject("id",UUID.class),ContentReportKind.valueOf(rs.getString("kind")),
                rs.getString("reason"),Status.valueOf(rs.getString("status")),rs.getLong("version"),
                instant(rs,"reported_at"),rs.getString("reporter_name"),rs.getString("target_label"));
    }

    private static Instant instant(ResultSet row,String name) throws SQLException {
        var value = row.getTimestamp(name);
        return value==null ? null : value.toInstant();
    }

    private static SoundConnectException invalid() { return new SoundConnectException(ErrorType.VALIDATION_ERROR); }
    private static SoundConnectException changed() { return new SoundConnectException(ErrorType.CONTENT_REPORT_STATE_CHANGED); }
}
