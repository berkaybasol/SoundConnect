package com.berkayb.soundconnect.modules.notification.push;

import com.berkayb.soundconnect.modules.notification.entity.Notification;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import static com.berkayb.soundconnect.modules.notification.push.StudioPushPresentation.Variant;

/** Push-only freshness checks. NOWAIT contention propagates to durable retry, never suppression.
 * Locks end with prepare(), before provider I/O. Historical inbox/read access is separate. */
@Service
@ConditionalOnProperty(name="app.notification.push.enabled", havingValue="true")
public class StudioPushEligibility {
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;
    public StudioPushEligibility(NamedParameterJdbcTemplate jdbc, @Qualifier("pushClock") Clock clock) {
        this.jdbc = jdbc; this.clock = clock;
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public Optional<Variant> resolve(Notification n) {
        if (n == null || n.getType() == null || !StudioPushPresentation.TYPES.contains(n.getType())
                || n.getRecipientId() == null || n.getPayload() == null) return Optional.empty();
        var p = n.getPayload(); UUID reservation = uuid(p,"reservationId"), room = uuid(p,"roomId"), profile = uuid(p,"studioProfileId");
        if (!"STUDIO".equals(text(p,"module")) || reservation == null || room == null || profile == null
                || uuid(p,"requesterId") == null) return Optional.empty();
        // Same profile -> room -> reservation order as owner commands. NOWAIT also
        // bounds the opposite account -> source order used by the push preflight.
        var studio = row("select user_id from tbl_studio_profile where id=:id for share nowait",profile);
        var space = row("select studio_profile_id,archived_at from tbl_studio_room where id=:id for share nowait",room);
        var source = row("""
                select room_id,requester_id,status,starts_at,ends_at,approval_required_snapshot,
                       decided_at,decided_by,cancelled_at,cancelled_by
                from tbl_studio_room_reservation where id=:id for share nowait
                """,reservation);
        if (studio == null || space == null || source == null || !profile.equals(uuid(space,"studio_profile_id"))
                || !room.equals(uuid(source,"room_id")) || !uuid(p,"requesterId").equals(uuid(source,"requester_id"))
                || !text(p,"status").equals(text(source,"status"))
                || !sameInstant(p,"startsAt",source,"starts_at") || !sameInstant(p,"endsAt",source,"ends_at")) return Optional.empty();
        UUID owner = uuid(studio,"user_id"), requester = uuid(source,"requester_id");
        if (owner == null || owner.equals(requester) || !usable(owner,true) || !usable(requester,false)) return Optional.empty();
        boolean ownerRecipient = switch (n.getType()) {
            case STUDIO_RESERVATION_CREATED, STUDIO_RESERVATION_CONFLICTING_REQUESTS,
                    STUDIO_RESERVATION_CANCELLED_BY_CUSTOMER -> true;
            default -> false;
        };
        if (!(ownerRecipient ? owner : requester).equals(n.getRecipientId())) return Optional.empty();
        String state = text(source,"status"), action = text(p,"action");
        Instant starts = instant(source,"starts_at"), ends = instant(source,"ends_at"), now = clock.instant();
        if (starts == null || ends == null || !ends.isAfter(starts)) return Optional.empty();
        boolean approval = Boolean.TRUE.equals(source.get("approval_required_snapshot"));
        boolean activeRoom = space.get("archived_at") == null;
        Variant variant = switch (n.getType()) {
            case STUDIO_RESERVATION_CREATED -> !"CREATED".equals(action) || !activeRoom ? null
                    : approval && "PENDING_APPROVAL".equals(state) && starts.isAfter(now) ? Variant.STUDIO_CREATED_PENDING
                    : !approval && "CONFIRMED".equals(state) && ends.isAfter(now) ? Variant.STUDIO_CREATED_CONFIRMED : null;
            case STUDIO_RESERVATION_CONFLICTING_REQUESTS -> "CONFLICTING_REQUESTS".equals(action) && activeRoom
                    && approval && "PENDING_APPROVAL".equals(state) && starts.isAfter(now)
                    && conflicting(room, starts, ends) ? Variant.STUDIO_CONFLICTING_REQUESTS : null;
            case STUDIO_RESERVATION_APPROVED -> "APPROVED".equals(action) && activeRoom && approval
                    && "CONFIRMED".equals(state) && decidedBy(source,owner,now) && ends.isAfter(now) ? Variant.STUDIO_APPROVED : null;
            case STUDIO_RESERVATION_REJECTED -> approval && "REJECTED_BY_STUDIO".equals(state) && decidedBy(source,owner,now)
                    ? "REJECTED".equals(action) ? Variant.STUDIO_REJECTED
                    : "AUTO_REJECTED_CONFLICT".equals(action) ? Variant.STUDIO_REJECTED_CONFLICT : null : null;
            case STUDIO_RESERVATION_CANCELLED_BY_CUSTOMER -> "CANCELLED_BY_CUSTOMER".equals(action)
                    && "CANCELLED_BY_CUSTOMER".equals(state) && cancelledBy(source,requester,now)
                    ? Variant.STUDIO_CANCELLED_BY_CUSTOMER : null;
            case STUDIO_RESERVATION_CANCELLED_BY_STUDIO -> !"CANCELLED_BY_STUDIO".equals(state) || !cancelledBy(source,owner,now) ? null
                    : "CANCELLED_BY_STUDIO".equals(action) ? Variant.STUDIO_CANCELLED_BY_STUDIO
                    : "CANCELLED_BY_STUDIO_ROOM_ARCHIVED".equals(action)
                    && Objects.equals(instant(source,"cancelled_at"),instant(space,"archived_at")) ? Variant.STUDIO_ROOM_ARCHIVED : null;
            default -> null;
        };
        // The schema does not persist the manual/automatic rejection reason.
        // AUTO_REJECTED_CONFLICT is a trusted domain-event distinction, not a
        // claim that this source query can independently prove that provenance.
        return Optional.ofNullable(variant);
    }

    private boolean usable(UUID id, boolean owner) {
        if (id == null || row("select id from tbl_user where id=:id and status='ACTIVE' and email_verified and erased_at is null for share nowait",id) == null) return false;
        var roles = jdbc.queryForList("""
                select r.name from user_roles m join tbl_role r on r.id=m.role_id
                where m.user_id=:id for share of m,r nowait
                """,Map.of("id",id),String.class);
        if (roles.isEmpty() || roles.contains("ROLE_LISTENER") || owner && !roles.contains("ROLE_STUDIO")) return false;
        return row("select id from \"tbl_listener-profile\" where user_id=:id for share nowait",id) == null;
    }
    private boolean conflicting(UUID room, Instant starts, Instant ends) {
        // Domain mutations serialize on the locked room, so the bounded count
        // cannot change before this transaction ends and needs no unbounded scan.
        return jdbc.queryForList("""
                select id from tbl_studio_room_reservation where room_id=:room and status='PENDING_APPROVAL'
                  and starts_at>:now and starts_at<:ends and ends_at>:starts order by id limit 2 for share nowait
                """,Map.of("room",room,"now",Timestamp.from(clock.instant()),"starts",Timestamp.from(starts),"ends",Timestamp.from(ends))).size() == 2;
    }
    private static boolean decidedBy(Map<String,Object> row, UUID owner, Instant now) {
        return owner.equals(uuid(row,"decided_by")) && presentBeforeStart(instant(row,"decided_at"),instant(row,"starts_at"),now);
    }
    private static boolean cancelledBy(Map<String,Object> row, UUID actor, Instant now) {
        return actor.equals(uuid(row,"cancelled_by")) && presentBeforeStart(instant(row,"cancelled_at"),instant(row,"starts_at"),now);
    }
    private static boolean presentBeforeStart(Instant at, Instant starts, Instant now) {
        return at != null && starts != null && !at.isAfter(now) && at.isBefore(starts);
    }
    private Map<String,Object> row(String sql, UUID id) {
        var rows = jdbc.queryForList(sql,Map.of("id",id)); return rows.isEmpty() ? null : rows.getFirst();
    }
    private static String text(Map<String,Object> p, String key) { return p.get(key) instanceof String value ? value : ""; }
    private static UUID uuid(Map<String,Object> p, String key) {
        Object value=p.get(key); if(value instanceof UUID id) return id;
        if(!(value instanceof String text)) return null;
        try { UUID id=UUID.fromString(text); return id.toString().equalsIgnoreCase(text) ? id : null; }
        catch(IllegalArgumentException invalid) { return null; }
    }
    private static boolean sameInstant(Map<String,Object> a,String ka,Map<String,Object> b,String kb) {
        Instant value=instant(a,ka); return value!=null && value.equals(instant(b,kb));
    }
    private static Instant instant(Map<String,Object> p,String key) {
        Object v=p.get(key); if(v instanceof Timestamp at) return at.toInstant();
        if(v instanceof OffsetDateTime at) return at.toInstant(); if(v instanceof Instant at) return at;
        if(v instanceof String text) try { return Instant.parse(text); } catch(java.time.DateTimeException invalid) { return null; }
        return null;
    }
}