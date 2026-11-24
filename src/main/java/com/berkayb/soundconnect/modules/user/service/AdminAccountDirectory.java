package com.berkayb.soundconnect.modules.user.service;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.admin.security.AdminPanelReadAccess;
import com.berkayb.soundconnect.modules.application.venueapplication.enums.ApplicationStatus;
import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileType;
import com.berkayb.soundconnect.modules.profile.shared.resolver.dto.UserProfileTargetDto;
import com.berkayb.soundconnect.modules.profile.shared.resolver.service.PublicProfileResolverService;
import com.berkayb.soundconnect.modules.role.enums.PermissionEnum;
import com.berkayb.soundconnect.modules.user.dto.response.AdminAccountDetail;
import com.berkayb.soundconnect.modules.user.dto.response.AdminAccountSummary;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.response.PageResponse;
import com.berkayb.soundconnect.shared.util.UsernameUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class AdminAccountDirectory {
    private static final Set<ProfileType> SUPPORTED = Set.of(ProfileType.LISTENER, ProfileType.MUSICIAN,
            ProfileType.VENUE, ProfileType.STUDIO);
    private static final Pattern UUID_FORMAT = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern PUBLIC_CODE = Pattern.compile("sc-[0-9a-f]{20}");

    // Applicants can have no personal role yet, and legacy profiles can outlive a missing role.
    // Conflicting personal identities must never select an arbitrary profile.
    private static final String ACCOUNTS = """
            with candidates as (
                select u.id, u.user_name, u.status, u.access_suspended from tbl_user u
                where u.erased_at is null and u.status is not null %s
            ), evidence(id, kind) as (
                select c.id, substring(r.name from 6) from candidates c
                join user_roles ur on ur.user_id=c.id join tbl_role r on r.id=ur.role_id
                where r.name in ('ROLE_LISTENER','ROLE_MUSICIAN','ROLE_VENUE','ROLE_STUDIO','ROLE_ORGANIZER','ROLE_PRODUCER')
                union all select c.id, 'LISTENER' from candidates c join "tbl_listener-profile" p on p.user_id=c.id
                union all select c.id, 'MUSICIAN' from candidates c join tbl_musician_profile p on p.user_id=c.id
                union all select c.id, 'VENUE' from candidates c join tbl_venues p on p.owner_id=c.id
                union all select c.id, 'STUDIO' from candidates c join tbl_studio_profile p on p.user_id=c.id
                union all select c.id, 'ORGANIZER' from candidates c join tbl_organizer_profile p on p.user_id=c.id
                union all select c.id, 'PRODUCER' from candidates c join tbl_producer_profile p on p.user_id=c.id
                union all select c.id, 'VENUE' from candidates c where c.status='PENDING_VENUE_REQUEST'
                union all select c.id, 'STUDIO' from candidates c where c.status in ('PENDING_STUDIO_REQUEST','REJECTED_STUDIO_REQUEST')
            ), personal as (
                select id, min(kind) as kind from evidence group by id having min(kind)=max(kind)
            ), matched as (
                select c.id, c.user_name, c.status, c.access_suspended, p.kind from candidates c join personal p on p.id=c.id
                where p.kind in ('LISTENER','MUSICIAN','VENUE','STUDIO') %s
            )
            """;
    private static final String PAGE = """
            select results.*, totals.total
            from (select count(*) as total from matched) totals
            left join (select * from matched order by user_name, id limit :size offset :offset) results on true
            order by results.user_name, results.id
            """;
    private static final String DETAIL = """
            select m.*, u.public_code, u.created_at, u.email_verified, u.access_revision,
                   d.reason, d.actor_username, d.decided_at,
                   case m.kind
                   when 'VENUE' then (select a.status from tbl_venue_applications a where a.user_id=u.id
                        order by a.application_date desc nulls last, a.created_at desc nulls last, a.id desc limit 1)
                   when 'STUDIO' then (select a.status from tbl_studio_applications a where a.applicant_id=u.id
                        order by a.application_date desc nulls last, a.created_at desc nulls last, a.id desc limit 1)
                   end as last_application_status
            from matched m join tbl_user u on u.id=m.id
            left join tbl_account_access_decision d on d.target_id=u.id and d.revision=u.access_revision
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final AdminPanelReadAccess panelAccess;
    private final PublicProfileResolverService publicProfiles;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<AdminAccountSummary> list(UserDetailsImpl actor, String query, ProfileType profileType,
                                                 UserStatus status, Boolean suspended, int page, int size) {
        panelAccess.require(actor, PermissionEnum.READ_USERS);
        if (page < 0 || page > 1000 || size < 1 || size > 100 || (query != null && query.length() > 80)
                || (profileType != null && !SUPPORTED.contains(profileType))) {
            throw new SoundConnectException(ErrorType.VALIDATION_ERROR);
        }
        var pageable = PageRequest.of(page, size);
        var params = new MapSqlParameterSource().addValue("size", size).addValue("offset", pageable.getOffset());
        String filters = searchFilter(query, params);
        if (suspended != null) {
            filters += " and u.access_suspended=:suspended";
            params.addValue("suspended", suspended);
        }
        if (status != null) {
            filters += " and u.status=:status";
            params.addValue("status", status.name());
        }
        String typeFilter = "";
        if (profileType != null) {
            typeFilter = "and p.kind=:profileType";
            params.addValue("profileType", profileType.name());
        }
        return jdbc.query(ACCOUNTS.formatted(filters, typeFilter) + PAGE, params, rows -> {
            // The count provides one row even when an out-of-range page has no matches.
            rows.next();
            long total = rows.getLong("total");
            List<AdminAccountSummary> accounts = new ArrayList<>();
            if (rows.getObject("id") != null) {
                do {
                    accounts.add(summary(rows));
                } while (rows.next());
            }
            return PageResponse.from(new PageImpl<>(accounts, pageable, total));
        });
    }

    private static String searchFilter(String query, MapSqlParameterSource params) {
        String term = UsernameUtils.normalize(query);
        if (term == null || term.isEmpty()) return "";
        if (UUID_FORMAT.matcher(term).matches()) {
            params.addValue("id", UUID.fromString(term));
            return " and u.id=:id";
        }
        if (PUBLIC_CODE.matcher(term).matches()) {
            params.addValue("code", term.toUpperCase(Locale.ROOT));
            return " and u.public_code=:code";
        }
        if (term.startsWith("@")) term = term.substring(1);
        if (term.isEmpty()) return " and false";
        params.addValue("term", term);
        return " and strpos(u.user_name, :term)>0";
    }

    // The public resolver holds the listener visibility row's SHARE lock through this same transaction.
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public AdminAccountDetail detail(UserDetailsImpl actor, UUID userId) {
        panelAccess.require(actor, PermissionEnum.READ_USERS);
        boolean canManage = panelAccess.allows(actor, PermissionEnum.MANAGE_USERS) && canChangeTarget(actor.getId(), userId);
        var account = jdbc.query(ACCOUNTS.formatted("and u.id=:id", "") + DETAIL, Map.of("id", userId), (row, index) -> {
            LocalDateTime created = row.getObject("created_at", LocalDateTime.class);
            String lastApplication = row.getString("last_application_status");
            OffsetDateTime decidedAt = row.getObject("decided_at", OffsetDateTime.class);
            var decision = decidedAt == null ? null : new AdminAccountDetail.AccessDecision(
                    row.getBoolean("access_suspended"), row.getString("reason"), row.getString("actor_username"), decidedAt.toInstant());
            return new AdminAccountDetail(summary(row), row.getString("public_code"),
                    created == null ? null : created.toInstant(ZoneOffset.UTC), row.getBoolean("email_verified"),
                    lastApplication == null ? null : ApplicationStatus.valueOf(lastApplication), null,
                    row.getLong("access_revision"), canManage, decision);
        }).stream().findFirst().orElseThrow(() -> new SoundConnectException(ErrorType.USER_NOT_FOUND));
        if (account.account().status() != UserStatus.ACTIVE || !account.emailVerified()) return account;
        List<UserProfileTargetDto> targets = publicProfiles.resolveByUserId(userId).profiles().stream()
                .filter(target -> account.account().profileType().name().equals(target.type()))
                .toList();
        if (targets.size() != 1) return account;
        return new AdminAccountDetail(account.account(), account.publicCode(), account.createdAt(),
                account.emailVerified(), account.lastApplicationStatus(),
                new AdminAccountDetail.ProfileTarget(account.account().profileType(), targets.get(0).profileId()),
                account.accessRevision(), account.canManageAccess(), account.lastAccessDecision());
    }

    private static AdminAccountSummary summary(ResultSet row) throws SQLException {
        return new AdminAccountSummary(row.getObject("id", UUID.class), row.getString("user_name"),
                ProfileType.valueOf(row.getString("kind")), UserStatus.valueOf(row.getString("status")), row.getBoolean("access_suspended"));
    }

    boolean canChangeTarget(UUID actorId, UUID targetId) {
        if (actorId.equals(targetId)) return false;
        return Boolean.TRUE.equals(jdbc.queryForObject(ACCOUNTS.formatted("and u.id=:id", "") + """
                select exists (
                    select 1 from matched m
                    where not exists (select 1 from user_roles ur join tbl_role r on r.id=ur.role_id
                        where ur.user_id=m.id and r.name in ('ROLE_OWNER','ROLE_ADMIN'))
                      and not exists (select 1 from user_permissions up join tbl_permissions p on p.id=up.permission_id
                        where up.user_id=m.id and p.name='ADMIN_PANEL_ACCESS')
                      and not exists (select 1 from user_roles ur join role_permissions rp on rp.role_id=ur.role_id
                        join tbl_permissions p on p.id=rp.permission_id where ur.user_id=m.id and p.name='ADMIN_PANEL_ACCESS')
                )
                """, Map.of("id", targetId), Boolean.class));
    }
}
