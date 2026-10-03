package com.berkayb.soundconnect.modules.notification.push;

import com.berkayb.soundconnect.modules.notification.entity.Notification;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.notification.support.BandNotificationIdentity;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import java.util.*;

/** V7 and subsequent explicitly compatible capabilities understand BAND. No actor, band name or business target crosses FCM. */
public final class BandPushPresentation {
    public static final String CAPABILITY = "ANDROID_NATIVE_V7";
    public static final String VERSION = "ANDROID_BAND_V1";
    public static final Set<NotificationType> TYPES = BandNotificationIdentity.TYPES;
    private BandPushPresentation() { }
    public static boolean supportsBand(String capability) { return CAPABILITY.equals(capability) || TablePushPresentation.supportsTable(capability); }
    public static boolean valid(String type, String variant) {
        return "DEFAULT".equals(variant) && TYPES.stream().anyMatch(t -> t.name().equals(type));
    }

    public static boolean validIdentity(Notification n) {
        if (n == null || !TYPES.contains(n.getType()) || n.getRecipientId() == null || n.getSourceEventId() == null) return false;
        var p = n.getPayload();
        var actor = BandNotificationIdentity.actorId(n.getType(), p);
        if (actor.isEmpty() || actor.get().equals(n.getRecipientId())) return false;
        var keys = new HashSet<>(Set.of("module", "bandId", "bandIdentityVersion", "action", BandNotificationIdentity.actorKey(n.getType())));
        if (n.getType().name().startsWith("BAND_INVITE_")) {
            keys.add("invitationId");
            if (BandNotificationIdentity.uuid(p,"invitationId").isEmpty()) return false;
        }
        return p.keySet().equals(keys);
    }

    /** Caller already owns the account fence. Lock aggregate before membership,
     * matching normal domain writes. A removed recipient is deliberately LEFT. */
    public static boolean eligible(Notification n, NamedParameterJdbcTemplate jdbc) {
        if (!validIdentity(n)) return false;
        UUID actor = BandNotificationIdentity.actorId(n.getType(),n.getPayload()).orElseThrow();
        UUID band = BandNotificationIdentity.uuid(n.getPayload(),"bandId").orElseThrow();
        var args = new HashMap<String,Object>();
        args.put("actor",actor); args.put("recipient",n.getRecipientId()); args.put("band",band);
        if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                select count(*)=2 from tbl_user u where u.id in (:actor,:recipient)
                  and u.erased_at is null and u.status='ACTIVE' and u.email_verified
                  and exists(select 1 from tbl_musician_profile p where p.user_id=u.id)
                  and exists(select 1 from user_roles ur join tbl_role r on r.id=ur.role_id
                      where ur.user_id=u.id and r.name in ('MUSICIAN','ROLE_MUSICIAN'))
                  and not exists(select 1 from user_roles ur join tbl_role r on r.id=ur.role_id
                      where ur.user_id=u.id and r.name in ('LISTENER','ROLE_LISTENER'))
                  and not exists(select 1 from "tbl_listener-profile" p where p.user_id=u.id)
                """,args,Boolean.class))) return false;
        if (jdbc.query("select id from tbl_band where id=:band for share",args,(rs,i)->rs.getObject(1)).isEmpty()) return false;
        var members=jdbc.query("""
                select user_id,band_role,status,invitation_id from tbl_band_member
                where band_id=:band and user_id in (:actor,:recipient) order by user_id for share
                """,args,(rs,i)->new Member(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getObject(4,UUID.class)));
        var a=members.stream().filter(m->m.user.equals(actor)).findFirst().orElse(null);
        var r=members.stream().filter(m->m.user.equals(n.getRecipientId())).findFirst().orElse(null);
        if (a==null || r==null) return false;
        UUID invitation=BandNotificationIdentity.uuid(n.getPayload(),"invitationId").orElse(null);
        return switch(n.getType()) {
            case BAND_INVITE_RECEIVED -> a.founder() && "PENDING".equals(r.status) && Objects.equals(invitation,r.invitation);
            case BAND_INVITE_ACCEPTED -> r.founder() && "ACTIVE".equals(a.status) && Objects.equals(invitation,a.invitation);
            case BAND_INVITE_REJECTED -> r.founder() && "REJECTED".equals(a.status) && Objects.equals(invitation,a.invitation);
            case BAND_MEMBER_REMOVED -> a.founder() && "LEFT".equals(r.status);
            case BAND_MEMBER_LEFT -> r.founder() && "LEFT".equals(a.status);
            default -> false;
        };
    }
    private record Member(UUID user,String role,String status,UUID invitation) {
        boolean founder() { return "FOUNDER".equals(role) && "ACTIVE".equals(status); }
    }
}
