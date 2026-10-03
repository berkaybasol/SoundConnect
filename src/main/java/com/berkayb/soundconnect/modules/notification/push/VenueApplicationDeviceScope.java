package com.berkayb.soundconnect.modules.notification.push;

import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Only for authenticated application-scoped device enrollment; never normal account admission. */
public final class VenueApplicationDeviceScope {
    private final NamedParameterJdbcTemplate jdbc;
    public VenueApplicationDeviceScope(NamedParameterJdbcTemplate jdbc) { this.jdbc=jdbc; }

    public void requireAllowed(UUID userId, UUID applicationId) {
        if(userId==null || applicationId==null) reject();
        var args=Map.of("user",userId,"application",applicationId);
        // Match decision-writer source/account order. Busy rows retry instead of deadlocking.
        var sources=jdbc.query("""
                select a.status,a.approved_venue_id from tbl_venue_applications a
                where a.id=:application and a.user_id=:user and a.id=(select latest.id
                  from tbl_venue_applications latest where latest.user_id=:user
                  order by latest.application_date desc,latest.created_at desc,latest.id desc limit 1)
                for share of a nowait
                """,args,(rs,index)->Map.entry(rs.getString(1),Optional.ofNullable(rs.getObject(2,UUID.class))));
        if(sources.isEmpty()) reject();
        var accounts=jdbc.query("""
                select status from tbl_user where id=:user and email_verified and erased_at is null for share nowait
                """,args,(rs,index)->rs.getString(1));
        if(accounts.isEmpty()) reject();
        boolean pending="PENDING_VENUE_REQUEST".equals(accounts.getFirst())
                && Set.of("PENDING","REJECTED").contains(sources.getFirst().getKey())
                && sources.getFirst().getValue().isEmpty()
                && Boolean.TRUE.equals(jdbc.queryForObject("""
                  select not exists(select 1 from user_roles where user_id=:user)
                    and not exists(select 1 from user_permissions where user_id=:user)
                  """,args,Boolean.class));
        boolean approved="ACTIVE".equals(accounts.getFirst()) && "APPROVED".equals(sources.getFirst().getKey())
                && sources.getFirst().getValue().isPresent()
                && Boolean.TRUE.equals(jdbc.queryForObject("""
                  select exists(select 1 from user_roles ur join tbl_role r on r.id=ur.role_id
                    where ur.user_id=:user and r.name='ROLE_VENUE')
                  """,args,Boolean.class));
        if(approved) approved=!jdbc.query("""
                select id from tbl_venues where id=:venue and owner_id=:user and status='APPROVED' for share nowait
                """,Map.of("venue",sources.getFirst().getValue().orElseThrow(),"user",userId),
                (rs,index)->rs.getObject(1,UUID.class)).isEmpty();
        if(!pending && !approved) reject();
    }
    private static void reject() { throw new SoundConnectException(ErrorType.FORBIDDEN_ACCESS); }
}
