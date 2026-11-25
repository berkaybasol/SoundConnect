package com.berkayb.soundconnect.modules.profile.shared.visibility;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.user.block.UserBlockService;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class ProfileBlockVisibility {
    private final UserBlockService blocks;
    private final NamedParameterJdbcTemplate jdbc;

    public static UUID viewerId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof UserDetailsImpl principal
                ? principal.getId() : null;
    }

    public boolean hidden(UUID ownerId) {
        return blocks.blockedEitherWay(viewerId(), ownerId);
    }

    public Set<UUID> hiddenOwners(Collection<UUID> ownerIds) {
        return blocks.blockedAmong(viewerId(), ownerIds);
    }

    public Set<UUID> hiddenOwners(UUID viewer, Collection<UUID> ownerIds) {
        return blocks.blockedAmong(viewer, ownerIds);
    }

    public void requireVisible(UUID ownerId) {
        if (hidden(ownerId)) throw new SoundConnectException(ErrorType.PROFILE_NOT_FOUND);
    }

    /** Target ids on profile, media and feed routes are not necessarily account ids. */
    public Set<UUID> hiddenTargets(UUID viewer, String type, Collection<UUID> ids) {
        if (viewer == null || ids.isEmpty()) return Set.of();
        String targetOwners = switch (type) {
            case "USER" -> "select id, id as owner from tbl_user where id in (:ids)";
            case "MUSICIAN", "MUSICIAN_PROFILE" -> "select id, user_id as owner from tbl_musician_profile where id in (:ids)";
            case "STUDIO", "STUDIO_PROFILE" -> "select id, user_id as owner from tbl_studio_profile where id in (:ids)";
            case "LISTENER", "LISTENER_PROFILE" -> "select id, user_id as owner from \"tbl_listener-profile\" where id in (:ids)";
            case "VENUE", "VENUE_PROFILE" -> """
                    select id, owner_id as owner from tbl_venues where id in (:ids)
                    union all select p.id, v.owner_id from tbl_venue_profile p join tbl_venues v on v.id=p.venue_id where p.id in (:ids)
                    """;
            case "EVENT_POST" -> "select post_id as id, user_id as owner from tbl_event_audience_intent where post_id in (:ids)";
            case "OVERTHINKING" -> """
                    select p.id, p.author_id as owner from tbl_overthinking_post p where p.id in (:ids)
                    and p.visibility_type='VISIBLE'
                    """;
            case "TABLE_GROUP_POST" -> "select id, owner_user_id as owner from tbl_table_group_profile_share where id in (:ids)";
            case "OVERTHINKING_PROFILE_SHARE" -> "select id, owner_user_id as owner from tbl_overthinking_profile_share where id in (:ids)";
            case "MEDIA" -> """
                    select m.id, case m.owner_type when 'USER' then m.owner_id when 'MUSICIAN_PROFILE' then musician.user_id
                        when 'LISTENER_PROFILE' then listener.user_id when 'STUDIO_PROFILE' then studio.user_id
                        when 'VENUE' then venue.owner_id when 'VENUE_PROFILE' then coalesce(profile_venue.owner_id,venue.owner_id) end as owner
                    from tbl_media_asset m
                    left join tbl_musician_profile musician on m.owner_type='MUSICIAN_PROFILE' and musician.id=m.owner_id
                    left join "tbl_listener-profile" listener on m.owner_type='LISTENER_PROFILE' and listener.id=m.owner_id
                    left join tbl_studio_profile studio on m.owner_type='STUDIO_PROFILE' and studio.id=m.owner_id
                    left join tbl_venues venue on m.owner_type in ('VENUE','VENUE_PROFILE') and venue.id=m.owner_id
                    left join tbl_venue_profile p on m.owner_type='VENUE_PROFILE' and p.id=m.owner_id
                    left join tbl_venues profile_venue on profile_venue.id=p.venue_id
                    where m.id in (:ids)
                    """;
            default -> null;
        };
        if (targetOwners == null) return Set.of();
        String sql = "with targets as (" + targetOwners + """
                ) select distinct t.id from targets t where exists (
                    select 1 from tbl_user_block b where b.unblocked_at is null and
                        ((b.blocker_id=:viewer and b.blocked_id=t.owner) or (b.blocked_id=:viewer and b.blocker_id=t.owner)))
                """;
        return Set.copyOf(jdbc.queryForList(sql, Map.of("viewer", viewer, "ids", ids), UUID.class));
    }

    public boolean hiddenTarget(String type, UUID id) {
        return id != null && !hiddenTargets(viewerId(), type, Set.of(id)).isEmpty();
    }

    public void requireVisibleTarget(String type, UUID id) {
        if (hiddenTarget(type, id)) throw new SoundConnectException(ErrorType.PROFILE_NOT_FOUND);
    }
}
