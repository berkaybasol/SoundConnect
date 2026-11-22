package com.berkayb.soundconnect.modules.application.admin;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.application.venueapplication.enums.ApplicationStatus;
import com.berkayb.soundconnect.modules.role.enums.PermissionEnum;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.response.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class PendingApplicationQuery {
    private final NamedParameterJdbcTemplate jdbc;

    public enum Kind {
        VENUE("tbl_venue_applications", "user_id", "venue_name", "venue_address",
                PermissionEnum.MANAGE_VENUE_APPLICATIONS, ErrorType.VENUE_APPLICATION_NOT_FOUND),
        STUDIO("tbl_studio_applications", "applicant_id", "studio_name", "studio_address",
                PermissionEnum.MANAGE_STUDIO_APPLICATIONS, ErrorType.STUDIO_APPLICATION_NOT_FOUND);

        final String table;
        final String applicantColumn;
        final String nameColumn;
        final String addressColumn;
        final PermissionEnum permission;
        final ErrorType notFound;

        Kind(String table, String applicantColumn, String nameColumn, String addressColumn,
             PermissionEnum permission, ErrorType notFound) {
            this.table = table;
            this.applicantColumn = applicantColumn;
            this.nameColumn = nameColumn;
            this.addressColumn = addressColumn;
            this.permission = permission;
            this.notFound = notFound;
        }

        String sources() {
            return """
                    from %s a
                    join tbl_user applicant on applicant.id=a.%s
                    join tbl_city city on city.id=a.city_id
                    join tbl_district district on district.id=a.district_id
                    left join tbl_neighborhood neighborhood on neighborhood.id=a.neighborhood_id
                    """.formatted(table, applicantColumn);
        }

        String select(boolean detail) {
            String fields = detail
                    ? "a." + addressColumn + " as address, a.phone, a.decision_date, a.decision_id, reviewer.user_name as reviewer, a.rejection_reason "
                    : "null as address, null as phone, null as decision_date, null as decision_id, null as reviewer, null as rejection_reason ";
            return "select a.id, a." + nameColumn + " as business_name, applicant.user_name, city.name as city_name, "
                    + "district.name as district_name, neighborhood.name as neighborhood_name, a.application_date, a.status, "
                    + fields + sources()
                    + (detail ? "left join tbl_user reviewer on reviewer.id=a.reviewed_by_id and reviewer.erased_at is null " : "")
                    + "where applicant.erased_at is null ";
        }
    }

    public PageResponse<PendingApplicationRow> list(UserDetailsImpl actor, Kind kind, int page, int size) {
        requireAccess(actor, kind);
        if (page < 0 || page > 1000 || size < 1 || size > 100) {
            throw new SoundConnectException(ErrorType.VALIDATION_ERROR);
        }
        var pageable = PageRequest.of(page, size);
        var rows = jdbc.query(kind.select(false) + " and a.status='PENDING' order by a.application_date desc, a.id desc limit :size offset :offset",
                Map.of("size", size, "offset", pageable.getOffset()), this::map);
        long total = jdbc.getJdbcTemplate().queryForObject("select count(*) " + kind.sources() + "where applicant.erased_at is null and a.status='PENDING'", Long.class);
        return PageResponse.from(new PageImpl<>(rows, pageable, total));
    }

    public PendingApplicationRow detail(UserDetailsImpl actor, Kind kind, UUID id) {
        return detail(actor, kind, id, true);
    }

    public PendingApplicationRow review(UserDetailsImpl actor, Kind kind, UUID id) {
        return detail(actor, kind, id, false);
    }

    private PendingApplicationRow detail(UserDetailsImpl actor, Kind kind, UUID id, boolean pendingOnly) {
        requireAccess(actor, kind);
        return jdbc.query(kind.select(true) + (pendingOnly ? " and a.status='PENDING'" : "") + " and a.id=:id", Map.of("id", id), this::map)
                .stream().findFirst().orElseThrow(() -> new SoundConnectException(kind.notFound));
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void requireDecisionAccess(UserDetailsImpl actor, Kind kind) {
        if (actor == null || actor.getId() == null) throw new SoundConnectException(ErrorType.UNAUTHORIZED);
        // Password reset / account erasure also lock this row. Keep the verified
        // session current through any wait for the application or applicant lock.
        var reviewers = jdbc.queryForList("select id from tbl_user where id=:id for update",
                Map.of("id", actor.getId()), UUID.class);
        if (reviewers.isEmpty()) throw new SoundConnectException(ErrorType.FORBIDDEN_ACCESS);
        requireAccess(actor, kind);
    }

    public void requireAccess(UserDetailsImpl actor, Kind kind) {
        if (actor == null || actor.getId() == null) throw new SoundConnectException(ErrorType.UNAUTHORIZED);
        // Evaluate role-derived and direct permissions against the same snapshot as the page and count.
        boolean allowed = Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists (
                    select 1 from tbl_user u
                    where u.id=:actor and u.status='ACTIVE' and u.email_verified and u.erased_at is null
                      and u.session_version=:sessionVersion
                      and not exists (select 1 from "tbl_listener-profile" lp where lp.user_id=u.id)
                      and not exists (select 1 from user_roles ur join tbl_role r on r.id=ur.role_id
                                      where ur.user_id=u.id and r.name='ROLE_LISTENER')
                      and 2 = (select count(*) from tbl_permissions p
                               where p.name in ('ADMIN_PANEL_ACCESS', :permission) and
                                 (exists (select 1 from user_permissions up where up.user_id=u.id and up.permission_id=p.id)
                                  or exists (select 1 from user_roles ur join role_permissions rp on rp.role_id=ur.role_id
                                             where ur.user_id=u.id and rp.permission_id=p.id)))
                )
                """, Map.of("actor", actor.getId(), "sessionVersion", actor.getUser().getSessionVersion(),
                "permission", kind.permission.name()), Boolean.class));
        if (!allowed) throw new SoundConnectException(ErrorType.FORBIDDEN_ACCESS);
    }

    private PendingApplicationRow map(ResultSet row, int index) throws SQLException {
        LocalDateTime decisionDate = row.getObject("decision_date", LocalDateTime.class);
        return new PendingApplicationRow(row.getObject("id", UUID.class), row.getString("business_name"),
                row.getString("user_name"), row.getString("city_name"), row.getString("district_name"),
                row.getString("neighborhood_name"), row.getObject("application_date", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                ApplicationStatus.valueOf(row.getString("status")), row.getString("address"), row.getString("phone"),
                decisionDate == null ? null : decisionDate.toInstant(ZoneOffset.UTC), row.getString("reviewer"),
                row.getString("rejection_reason"), row.getObject("decision_id", UUID.class));
    }
}
