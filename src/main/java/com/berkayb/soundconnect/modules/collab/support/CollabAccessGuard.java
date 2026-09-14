package com.berkayb.soundconnect.modules.collab.support;

import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Current scalar identity checks also protect service callers and legacy mixed-role accounts. */
@Component
@RequiredArgsConstructor
public class CollabAccessGuard {
    private static final Set<String> BUSINESS_ROLES = Set.of("ROLE_MUSICIAN", "ROLE_VENUE", "ROLE_STUDIO");
    private static final Set<String> PERSONAL_ROLES = Set.of("ROLE_MUSICIAN", "ROLE_VENUE", "ROLE_STUDIO",
            "ROLE_LISTENER", "ROLE_ORGANIZER", "ROLE_PRODUCER");
    private final UserRepository users;
    private final NamedParameterJdbcTemplate jdbc;

    public void requireBackstage(UUID viewerId) {
        requireIdentity(viewerId);
        Set<String> roles = users.findRoleNamesByUserId(viewerId);
        Set<String> profiles = users.findExistingPersonalProfileRoleNames(viewerId);
        if (profiles.size() != 1 || profiles.stream().noneMatch(BUSINESS_ROLES::contains)
                || roles.stream().filter(PERSONAL_ROLES::contains).count() != 1
                || !roles.containsAll(profiles) || !active(viewerId)) throw forbidden();
    }

    public void requireModerator(UUID viewerId) {
        requireIdentity(viewerId);
        if (users.findRoleNamesByUserId(viewerId).contains("ROLE_LISTENER")
                || users.findExistingPersonalProfileRoleNames(viewerId).contains("ROLE_LISTENER")) throw forbidden();
        boolean allowed = Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from tbl_user u where u.id=:id and u.status='ACTIVE'
                    and u.email_verified and u.erased_at is null
                    and (exists(select 1 from user_permissions up join tbl_permissions permission on permission.id=up.permission_id
                            where up.user_id=u.id and permission.name='MANAGE_COLLAB_REPORTS')
                        or exists(select 1 from user_roles ur join role_permissions rp on rp.role_id=ur.role_id
                            join tbl_permissions permission on permission.id=rp.permission_id
                            where ur.user_id=u.id and permission.name='MANAGE_COLLAB_REPORTS')))
                """, Map.of("id", viewerId), Boolean.class));
        if (!allowed) throw forbidden();
    }

    private boolean active(UUID viewerId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from tbl_user where id=:id and status='ACTIVE' and email_verified and erased_at is null)
                """, Map.of("id", viewerId), Boolean.class));
    }
    private void requireIdentity(UUID viewerId) {
        if (viewerId == null) throw new SoundConnectException(ErrorType.UNAUTHORIZED);
    }
    private SoundConnectException forbidden() { return new SoundConnectException(ErrorType.COLLAB_FORBIDDEN); }
}
