package com.berkayb.soundconnect.modules.notification.push;

import com.berkayb.soundconnect.modules.event.plan.EventPlanDefinition;
import com.berkayb.soundconnect.modules.event.plan.EventPlanRules;
import com.berkayb.soundconnect.modules.event.plan.EventPlanTemplate;
import com.berkayb.soundconnect.modules.event.support.EventScheduleClock;
import com.berkayb.soundconnect.modules.notification.entity.Notification;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
import static com.berkayb.soundconnect.modules.notification.push.VenuePushPresentation.Variant;

/**
 * Push-only source check: historical inbox admission is intentionally unchanged.
 * Source locks never wait behind domain transactions (which have differing account
 * lock order). A NOWAIT conflict propagates to the existing durable push retry.
 * Locks end with prepare(), before any provider I/O; accepted sends cannot be recalled.
 */
@Service
@ConditionalOnProperty(name="app.notification.push.enabled", havingValue="true")
public class VenuePushEligibility {
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;
    private static final ObjectMapper JSON = new ObjectMapper();
    public VenuePushEligibility(NamedParameterJdbcTemplate jdbc, @Qualifier("pushClock") Clock clock) {
        this.jdbc = jdbc; this.clock = clock;
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public Optional<Variant> resolve(Notification notification) {
        if (notification == null || notification.getType() == null || !VenuePushPresentation.TYPES.contains(notification.getType())
                || notification.getRecipientId() == null || notification.getPayload() == null) return Optional.empty();
        var payload = notification.getPayload();
        var module = text(payload, "module");
        Variant result;
        if (VenuePushPresentation.connection(notification.getType())) {
            result = "ARTIST_VENUE".equals(module) ? connection(notification) : null;
        } else result = switch (module) {
            case "EVENT_PERFORMER" -> event(notification);
            case "EVENT_PLAN" -> plan(notification);
            default -> null;
        };
        return Optional.ofNullable(result);
    }

    private Variant connection(Notification n) {
        var p = n.getPayload(); var id = uuid(p, "requestId");
        if (id == null) return null;
        // Scalar preflight establishes band-first order without retaining a JPA snapshot.
        var pre = row("select band_id from artist_venue_connection_requests where id=:id", id);
        if (pre == null || !lockBand(uuid(pre,"band_id"))) return null;
        var source = row("select * from artist_venue_connection_requests where id=:id for share nowait", id);
        if (source == null || !Objects.equals(uuid(pre,"band_id"),uuid(source,"band_id"))) return null;
        String action = switch (n.getType()) {
            case ARTIST_VENUE_LINK_APPLICATION_REQUEST -> "REQUEST_CREATED";
            case ARTIST_VENUE_LINK_APPLICATION_ACCEPT -> "REQUEST_ACCEPTED";
            default -> "REQUEST_REJECTED";
        };
        String status = switch (n.getType()) {
            case ARTIST_VENUE_LINK_APPLICATION_REQUEST -> "PENDING";
            case ARTIST_VENUE_LINK_APPLICATION_ACCEPT -> "ACCEPTED";
            default -> "REJECTED";
        };
        if (!action.equals(text(p,"action")) || !status.equals(text(source,"status"))
                || !status.equals(text(p,"status")) || !sameId(source,"venue_id",p,"venueId")
                || !sameTarget(source,p) || !text(source,"request_by_type").equals(text(p,"requestByType"))) return null;
        String direction = text(source,"request_by_type");
        if (!Set.of("VENUE","ARTIST","BAND").contains(direction)
                || "ARTIST".equals(direction) && uuid(source,"musician_profile_id") == null
                || "BAND".equals(direction) && uuid(source,"band_id") == null) return null;
        UUID owner = venueOwner(uuid(source,"venue_id"));
        var target = target(uuid(source,"musician_profile_id"),uuid(source,"band_id"),false);
        if (owner == null || target == null || !usable(owner) || !target.usableRepresentative()) return null;
        boolean request = n.getType() == NotificationType.ARTIST_VENUE_LINK_APPLICATION_REQUEST;
        boolean ownerRecipient = request != "VENUE".equals(direction);
        return (ownerRecipient ? owner.equals(n.getRecipientId())
                : target.recipients().contains(n.getRecipientId())) && usable(n.getRecipientId()) ? Variant.DEFAULT : null;
    }

    private Variant event(Notification n) {
        var p = n.getPayload(); UUID requestId = uuid(p,"requestId"), eventId = uuid(p,"eventId");
        if (requestId == null || eventId == null) return null;
        var pre = row("select band_id,event_id from event_performer_requests where id=:id",requestId);
        if (pre == null || !eventId.equals(uuid(pre,"event_id")) || !lockBand(uuid(pre,"band_id"))) return null;
        var event = row("select * from tbl_event where id=:id for share nowait",eventId);
        var source = row("select * from event_performer_requests where id=:id for share nowait",requestId);
        if (event == null || source == null || !"VENUE".equals(text(event,"event_origin"))
                || !eventId.equals(uuid(source,"event_id"))
                || !Objects.equals(uuid(pre,"band_id"),uuid(source,"band_id"))
                || !sameId(event,"venue_id",p,"venueId") || !sameTarget(source,p)) return null;
        String purpose = text(source,"request_purpose"), status = state(n.getType());
        if (!Set.of("PERFORMER_CONSENT","PROFILE_VISIBILITY").contains(purpose)
                || !purpose.equals(text(p,"requestPurpose")) || !status.equals(text(source,"status"))
                || !status.equals(text(p,"status")) || !eventAction(n.getType()).equals(text(p,"action"))) return null;
        boolean request = n.getType() == NotificationType.EVENT_PERFORMER_APPROVAL_REQUESTED;
        if (request && !futureEvent(event)) return null;
        boolean linked = "PROFILE_VISIBILITY".equals(purpose) || "ACCEPTED".equals(status);
        if (linked) {
            if (!"APPROVED".equals(text(event,"performer_approval_status"))
                    || !sameNullableId(event,"musician_profile_id",source,"musician_profile_id")
                    || !sameNullableId(event,"band_id",source,"band_id")) return null;
        } else if (!status.equals(text(event,"performer_approval_status"))) return null;
        UUID owner = venueOwner(uuid(event,"venue_id"));
        var target = target(uuid(source,"musician_profile_id"),uuid(source,"band_id"),true);
        if (owner == null || !owner.equals(uuid(event,"organizer_user_id")) || !usable(owner)
                || target == null || !target.usableRepresentative() || !usable(n.getRecipientId())) return null;
        if (request ? !target.recipients().contains(n.getRecipientId()) : !owner.equals(n.getRecipientId())) return null;
        return "PROFILE_VISIBILITY".equals(purpose) ? Variant.PROFILE_VISIBILITY : Variant.DEFAULT;
    }

    private Variant plan(Notification n) {
        var p = n.getPayload(); UUID id = uuid(p,"planId");
        if (id == null) return null;
        var pre = row("select band_id from event_plans where id=:id",id);
        if (pre == null || !lockBand(uuid(pre,"band_id"))) return null;
        var source = row("select * from event_plans where id=:id for share nowait",id);
        if (source == null || !Objects.equals(uuid(pre,"band_id"),uuid(source,"band_id"))
                || !sameId(source,"venue_id",p,"venueId")
                || !text(source,"consent_revision").equals(text(p,"consentRevision"))) return null;
        boolean band = uuid(source,"band_id") != null;
        if (!(band ? "BAND" : "MUSICIAN").equals(text(p,"targetType"))
                || !sameId(source,band ? "band_id" : "musician_profile_id",p,"targetId")) return null;
        String action = text(p,"action"), expected;
        boolean request = n.getType() == NotificationType.EVENT_PERFORMER_APPROVAL_REQUESTED;
        if (request) { if (!"REQUESTED".equals(action)) return null; expected = "PENDING"; }
        else if (n.getType() == NotificationType.EVENT_PERFORMER_APPROVED) {
            if (!"ACCEPT".equals(action)) return null; expected = "ACCEPTED";
        } else {
            if (!Set.of("REJECT","WITHDRAW").contains(action)) return null;
            expected = "WITHDRAW".equals(action) ? "WITHDRAWN" : "REJECTED";
        }
        if (!expected.equals(text(source,"consent_status")) || !expected.equals(text(p,"consentStatus"))) return null;
        if (request && (!"ACTIVE".equals(text(source,"status")) || !futurePlan(source))) return null;
        UUID owner = venueOwner(uuid(source,"venue_id"));
        var target = target(uuid(source,"musician_profile_id"),uuid(source,"band_id"),true);
        if (owner == null || !owner.equals(uuid(source,"organizer_user_id")) || !usable(owner)
                || target == null || !target.usableRepresentative() || !usable(n.getRecipientId())) return null;
        if (request ? !target.recipients().contains(n.getRecipientId()) : !owner.equals(n.getRecipientId())) return null;
        return "WITHDRAW".equals(action) ? Variant.PLAN_WITHDRAWN : Variant.PLAN_CONSENT;
    }

    private boolean futureEvent(Map<String,Object> row) {
        var date = date(row,"event_date"); var time = time(row,"start_time");
        return date != null && time != null && date.atTime(time).atZone(EventScheduleClock.ZONE).toInstant().isAfter(clock.instant());
    }
    private boolean futurePlan(Map<String,Object> row) {
        try {
            int mask = Integer.parseInt(text(row,"weekday_mask"));
            var weekdays = new ArrayList<Integer>();
            for (int day=1;day<=7;day++) if ((mask & (1 << (day-1))) != 0) weekdays.add(day);
            var json = JSON.readTree(text(row,"excluded_dates"));
            if (!json.isArray() || json.size()>EventPlanRules.MAX_EXCLUDED_DATES) return false;
            var excluded = new ArrayList<LocalDate>();
            for (var value : json) excluded.add(LocalDate.parse(value.asText()));
            var definition = new EventPlanDefinition(uuid(row,"venue_id"),date(row,"start_date"),date(row,"until_date"),
                    weekdays,excluded,new EventPlanTemplate("",null,time(row,"start_time"),null,null,null,null,null));
            return definition.startDate()!=null && definition.template().startTime()!=null && !weekdays.isEmpty()
                    && EventPlanRules.hasFuture(definition,clock.instant());
        } catch (JsonProcessingException | IllegalArgumentException | DateTimeException invalid) { return false; }
    }
    private UUID venueOwner(UUID venueId) {
        if (venueId == null) return null;
        var venue = row("select owner_id,status from tbl_venues where id=:id for share nowait",venueId);
        return venue != null && "APPROVED".equals(text(venue,"status")) ? uuid(venue,"owner_id") : null;
    }
    private boolean lockBand(UUID bandId) {
        return bandId == null || row("select id from tbl_band where id=:id for share nowait",bandId) != null;
    }
    private Target target(UUID musician, UUID band, boolean foundersOnly) {
        if ((musician == null) == (band == null)) return null;
        if (musician != null) {
            var profile = row("select user_id from tbl_musician_profile where id=:id for share nowait",musician);
            UUID user = profile == null ? null : uuid(profile,"user_id");
            return user == null ? null : new Target(Set.of(user),usable(user));
        }
        var members = jdbc.queryForList("""
                select m.user_id,m.band_role from tbl_band_member m join tbl_user u on u.id=m.user_id
                where m.band_id=:id and m.status='ACTIVE' and u.status='ACTIVE'
                  and u.email_verified and u.erased_at is null
                order by m.user_id limit 1001 for share of m,u nowait
                """,Map.of("id",band));
        if (members.size()>1000) return null;
        var recipients = new HashSet<UUID>(); boolean representative = false;
        for (var member : members) {
            String role = text(member,"band_role"); UUID user = uuid(member,"user_id");
            if (user == null) continue;
            boolean manager = "FOUNDER".equals(role) || !foundersOnly && "MANAGER".equals(role);
            if (!foundersOnly || "FOUNDER".equals(role)) recipients.add(user);
            representative |= manager;
        }
        return new Target(Set.copyOf(recipients),representative);
    }
    private boolean usable(UUID id) {
        if (id == null) return false;
        var user = row("select id from tbl_user where id=:id and status='ACTIVE' and email_verified and erased_at is null for share nowait",id);
        return user != null;
    }
    private Map<String,Object> row(String sql, UUID id) {
        var rows = jdbc.queryForList(sql,Map.of("id",id));
        return rows.isEmpty() ? null : rows.getFirst();
    }
    private static boolean sameTarget(Map<String,Object> source,Map<String,Object> payload) {
        return sameNullableId(source,"musician_profile_id",payload,"musicianProfileId")
                && sameNullableId(source,"band_id",payload,"bandId");
    }
    private static boolean sameId(Map<String,Object> a,String ka,Map<String,Object> b,String kb) {
        UUID id = uuid(a,ka); return id != null && id.equals(uuid(b,kb));
    }
    private static boolean sameNullableId(Map<String,Object> a,String ka,Map<String,Object> b,String kb) {
        // Invalid non-null UUIDs never become equivalent to an absent optional identity.
        if (a.get(ka) != null && uuid(a,ka) == null || b.get(kb) != null && uuid(b,kb) == null) return false;
        return Objects.equals(uuid(a,ka),uuid(b,kb));
    }
    private static UUID uuid(Map<String,Object> row,String key) {
        try { Object value=row.get(key); return value==null ? null : UUID.fromString(value.toString()); }
        catch (IllegalArgumentException invalid) { return null; }
    }
    private static String text(Map<String,Object> row,String key) {
        Object value=row.get(key); return value==null ? "" : value.toString();
    }
    private static LocalDate date(Map<String,Object> row,String key) {
        Object value=row.get(key); return value instanceof java.sql.Date d ? d.toLocalDate() : value instanceof LocalDate d ? d : null;
    }
    private static LocalTime time(Map<String,Object> row,String key) {
        Object value=row.get(key); return value instanceof java.sql.Time t ? t.toLocalTime() : value instanceof LocalTime t ? t : null;
    }
    private static String state(NotificationType type) {
        return switch(type) { case EVENT_PERFORMER_APPROVAL_REQUESTED -> "PENDING"; case EVENT_PERFORMER_APPROVED -> "ACCEPTED"; default -> "REJECTED"; };
    }
    private static String eventAction(NotificationType type) {
        return switch(type) { case EVENT_PERFORMER_APPROVAL_REQUESTED -> "APPROVAL_REQUESTED"; case EVENT_PERFORMER_APPROVED -> "APPROVED"; default -> "REJECTED"; };
    }
    private record Target(Set<UUID> recipients,boolean usableRepresentative) { }
}
