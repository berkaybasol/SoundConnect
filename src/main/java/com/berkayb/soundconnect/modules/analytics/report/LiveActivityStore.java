package com.berkayb.soundconnect.modules.analytics.report;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.user.service.MemberAnalyticsPreferenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;

import java.util.Map;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class LiveActivityStore {
    private final NamedParameterJdbcTemplate jdbc;

    public void guest(UUID installationId) {
        jdbc.update("""
                insert into tbl_analytics_live_activity(minute_at,audience,actor_id)
                values(date_trunc('minute',clock_timestamp()),'GUEST',:id)
                on conflict do nothing
                """, Map.of("id", installationId));
    }

    @Transactional
    public void member(UserDetailsImpl actor, UUID grant) {
        // Read the currently authorized role from the same account history as membership reporting.
        // Hold the account and preference locks through insertion, including concurrent withdrawal.
        jdbc.update("""
                with allowed as materialized (
                    select m.profile_type from tbl_user u
                    join tbl_member_analytics_preference p on p.user_id=u.id
                    join tbl_analytics_membership_period m on m.user_id=u.id and m.state='ACTIVE' and m.ended_at is null
                    where u.id=:user and u.session_version=:session and u.erased_at is null
                      and u.status='ACTIVE' and u.email_verified and not u.access_suspended
                      and p.decision='GRANTED' and p.policy_version=:policy and p.analytics_user_id=:grant
                      and m.profile_type in ('LISTENER','MUSICIAN','VENUE','STUDIO')
                      and not exists (select from user_roles ur join tbl_role r on r.id=ur.role_id
                                      where ur.user_id=u.id and r.name in ('ROLE_ADMIN','ROLE_OWNER'))
                    for share of u,p
                )
                insert into tbl_analytics_live_activity(minute_at,audience,actor_id)
                select date_trunc('minute',clock_timestamp()),profile_type,:actor from allowed
                on conflict do nothing
                """, Map.of("user", actor.getId(), "session", actor.getUser().getSessionVersion(),
                "policy", MemberAnalyticsPreferenceService.POLICY_VERSION, "grant", grant,
                "actor", UUID.nameUUIDFromBytes(("soundconnect:live-member:" + actor.getId()).getBytes(StandardCharsets.UTF_8))));
    }
}
