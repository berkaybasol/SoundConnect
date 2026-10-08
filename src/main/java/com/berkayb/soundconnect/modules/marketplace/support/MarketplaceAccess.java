package com.berkayb.soundconnect.modules.marketplace.support;

import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.UUID;

/** Authoritative current account eligibility, shared by listing queries and private media. */
@Component
@RequiredArgsConstructor
public class MarketplaceAccess {
    private final NamedParameterJdbcTemplate jdbc;

    public void requireBackstage(UUID userId) {
        if (userId == null) throw new SoundConnectException(ErrorType.UNAUTHORIZED);
        if (!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from tbl_user u where u.id=:id and "
                + eligibleOwnerSql("u") + ")", Map.of("id", userId), Boolean.class))) {
            throw new SoundConnectException(ErrorType.MARKETPLACE_FORBIDDEN);
        }
    }

    /** Only trusted Java aliases may be supplied; this is never request text. */
    public static String eligibleOwnerSql(String alias) {
        if (!alias.matches("[a-z][a-z0-9_]*")) throw new IllegalArgumentException("Invalid SQL alias");
        return ("""
                %1$s.status='ACTIVE' and %1$s.email_verified and %1$s.erased_at is null
                and not exists(select 1 from "tbl_listener-profile" p where p.user_id=%1$s.id)
                and not exists(select 1 from tbl_organizer_profile p where p.user_id=%1$s.id)
                and not exists(select 1 from tbl_producer_profile p where p.user_id=%1$s.id)
                and (case when exists(select 1 from tbl_musician_profile p where p.user_id=%1$s.id) then 1 else 0 end
                   + case when exists(select 1 from tbl_studio_profile p where p.user_id=%1$s.id) then 1 else 0 end
                   + case when exists(select 1 from tbl_venues p where p.owner_id=%1$s.id) then 1 else 0 end)=1
                and (select count(distinct r.name) from user_roles ur join tbl_role r on r.id=ur.role_id
                     where ur.user_id=%1$s.id and r.name in
                     ('ROLE_MUSICIAN','ROLE_STUDIO','ROLE_VENUE','ROLE_LISTENER','ROLE_ORGANIZER','ROLE_PRODUCER'))=1
                and exists(select 1 from user_roles ur join tbl_role r on r.id=ur.role_id where ur.user_id=%1$s.id and
                    ((r.name='ROLE_MUSICIAN' and exists(select 1 from tbl_musician_profile p where p.user_id=%1$s.id))
                    or (r.name='ROLE_STUDIO' and exists(select 1 from tbl_studio_profile p where p.user_id=%1$s.id))
                    or (r.name='ROLE_VENUE' and exists(select 1 from tbl_venues p where p.owner_id=%1$s.id))))
                """).formatted(alias);
    }

    public void requireModerator(UUID userId) {
        if (userId == null) throw new SoundConnectException(ErrorType.UNAUTHORIZED);
        boolean allowed = Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from tbl_user u where u.id=:id and u.status='ACTIVE'
                and u.email_verified and u.erased_at is null
                and not exists(select 1 from "tbl_listener-profile" p where p.user_id=u.id)
                and not exists(select 1 from user_roles ur join tbl_role r on r.id=ur.role_id
                    where ur.user_id=u.id and r.name='ROLE_LISTENER')
                and (exists(select 1 from user_permissions up join tbl_permissions p on p.id=up.permission_id
                       where up.user_id=u.id and p.name='MANAGE_MARKETPLACE_REPORTS')
                    or exists(select 1 from user_roles ur join role_permissions rp on rp.role_id=ur.role_id
                       join tbl_permissions p on p.id=rp.permission_id
                       where ur.user_id=u.id and p.name='MANAGE_MARKETPLACE_REPORTS')))
                """, Map.of("id", userId), Boolean.class));
        if (!allowed) throw new SoundConnectException(ErrorType.MARKETPLACE_FORBIDDEN);
    }

    public static String eligibleListingProfileSql(String alias) {
        if (!alias.matches("[a-z][a-z0-9_]*")) throw new IllegalArgumentException("Invalid SQL alias");
        return ("""
                ((%1$s.seller_profile_type='MUSICIAN' and exists(select 1 from tbl_musician_profile p
                    where p.id=%1$s.seller_profile_id and p.user_id=%1$s.owner_user_id))
                or (%1$s.seller_profile_type='STUDIO' and exists(select 1 from tbl_studio_profile p
                    where p.id=%1$s.seller_profile_id and p.user_id=%1$s.owner_user_id))
                or (%1$s.seller_profile_type='VENUE' and exists(select 1 from tbl_venues p
                    where p.id=%1$s.seller_profile_id and p.owner_id=%1$s.owner_user_id)))
                """).formatted(alias);
    }
}
