package com.berkayb.soundconnect.modules.admin.security;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.role.enums.PermissionEnum;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class AdminPanelReadAccess {
    public static final String PANEL = "hasAuthority('ADMIN_PANEL_ACCESS') and !hasRole('LISTENER')";
    private final NamedParameterJdbcTemplate jdbc;

    /** Evaluate the actor and both direct or role-derived permissions in the caller's data snapshot. */
    public void require(UserDetailsImpl actor, PermissionEnum permission) {
        if (actor == null || actor.getId() == null) throw new SoundConnectException(ErrorType.UNAUTHORIZED);
        if (!allows(actor, permission)) throw new SoundConnectException(ErrorType.FORBIDDEN_ACCESS);
    }

    public boolean allows(UserDetailsImpl actor, PermissionEnum permission) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists (
                    select 1 from tbl_user u
                    where u.id=:actor and u.status='ACTIVE' and u.email_verified and u.erased_at is null
                      and u.session_version=:sessionVersion and not u.access_suspended
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
                "permission", permission.name()), Boolean.class));
    }
}
