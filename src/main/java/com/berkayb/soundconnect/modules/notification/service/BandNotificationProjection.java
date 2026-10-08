package com.berkayb.soundconnect.modules.notification.service;

import com.berkayb.soundconnect.modules.notification.dto.response.NotificationResponseDto;
import com.berkayb.soundconnect.modules.notification.support.BandNotificationIdentity;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Scalar reads avoid OSIV snapshots. Locks live through the caller's delivery transaction. */
@Service
@RequiredArgsConstructor
public class BandNotificationProjection {
    private final NamedParameterJdbcTemplate jdbc;

    @Transactional(propagation = Propagation.MANDATORY)
    public Map<UUID,NotificationResponseDto> project(List<NotificationResponseDto> notifications) {
        var bands = new TreeSet<UUID>();
        var actors = new TreeSet<UUID>();
        for (var n : notifications) {
            if (n == null || !BandNotificationIdentity.applies(n.type())) continue;
            BandNotificationIdentity.actorId(n.type(),n.payload()).ifPresent(id -> {
                actors.add(id); actors.add(n.recipientId());
                BandNotificationIdentity.uuid(n.payload(),"bandId").ifPresent(bands::add);
            });
        }
        Map<UUID,String> names = new HashMap<>(), bandNames = new HashMap<>();
        if (!actors.isEmpty()) {
            // Account fence first, then band aggregate: same delivery ordering as existing policy.
            jdbc.query("select id from tbl_user where id in (:ids) order by id for share",
                    Map.of("ids",actors),(rs,row) -> rs.getObject(1,UUID.class));
            jdbc.query("""
                    select u.id,u.user_name from tbl_user u
                    where u.id in (:ids) and u.erased_at is null and u.status='ACTIVE' and u.email_verified
                      and exists(select 1 from user_roles ur join tbl_role r on r.id=ur.role_id
                        where ur.user_id=u.id and r.name='ROLE_MUSICIAN')
                      and exists(select 1 from tbl_musician_profile p where p.user_id=u.id)
                      and not exists(select 1 from user_roles ur join tbl_role r on r.id=ur.role_id
                        where ur.user_id=u.id and r.name='ROLE_LISTENER')
                      and not exists(select 1 from "tbl_listener-profile" lp where lp.user_id=u.id)
                    """,Map.of("ids",actors),(rs,row) -> {
                names.put(rs.getObject(1,UUID.class),rs.getString(2)); return 0;
            });
        }
        if (!bands.isEmpty()) jdbc.query("select id,name from tbl_band where id in (:ids) order by id for share",
                Map.of("ids",bands),(rs,row) -> {
                    bandNames.put(rs.getObject(1,UUID.class),rs.getString(2)); return 0;
                });
        var result = new HashMap<UUID,NotificationResponseDto>();
        for (var n : notifications) {
            if (n == null || !BandNotificationIdentity.applies(n.type())) continue;
            var payload = BandNotificationIdentity.payload(n.type(),n.payload());
            String actor = BandNotificationIdentity.actorId(n.type(),payload).map(names::get).orElse(null);
            String band = BandNotificationIdentity.uuid(payload,"bandId").map(bandNames::get).orElse(null);
            String title = BandNotificationIdentity.title(n.type()), message = BandNotificationIdentity.MESSAGE;
            if (actor != null && band != null && names.containsKey(n.recipientId())) {
                // Public band names are readable through the existing public band endpoint.
                // This projection grants no invitation, membership or target access.
                title = switch (n.type()) {
                    case BAND_INVITE_RECEIVED -> band + " seni gruba davet etti";
                    case BAND_INVITE_ACCEPTED -> actor + " grup davetini kabul etti";
                    case BAND_INVITE_REJECTED -> actor + " grup davetini reddetti";
                    case BAND_MEMBER_REMOVED -> band + " grubundan çıkarıldın";
                    case BAND_MEMBER_LEFT -> actor + " gruptan ayrıldı";
                    default -> title;
                };
                message = n.type() == com.berkayb.soundconnect.modules.notification.enums.NotificationType.BAND_INVITE_RECEIVED
                        ? actor + " tarafından davet aldın." : band + " grubuna ait üyelik bildirimi.";
            }
            result.put(n.id(),new NotificationResponseDto(n.id(),n.recipientId(),n.type(),title,message,
                    n.read(),n.createdAt(),payload));
        }
        return result;
    }
}
