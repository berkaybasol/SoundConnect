package com.berkayb.soundconnect.modules.promotion.announcement;

import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileType;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Optional;

/** One audience/lifecycle boundary for directory, media, engagement, feed replay and analytics. */
@Component
@RequiredArgsConstructor
public class AnnouncementAccess {
    public static final Set<ProfileType> PROFILES = Set.of(
            ProfileType.MUSICIAN, ProfileType.LISTENER, ProfileType.VENUE, ProfileType.STUDIO);

    /** Uses alias p and the :now (UTC timestamp), :profile parameters. */
    public static final String VISIBLE_SQL = """
            p.placement='FEED' and p.type='ANNOUNCEMENT' and p.status='ACTIVE'
            and p.first_published_at is not null
            and p.start_date<=:now and (p.end_date is null or p.end_date>:now)
            and exists(select 1 from tbl_promotion_audience audience
                where audience.promotion_id=p.id and audience.profile_type=:profile)
            and (p.media_asset_id is null or exists(select 1 from tbl_media_asset media
                where media.id=p.media_asset_id and media.owner_type='PROMOTION' and media.owner_id=p.id
                  and media.visibility='PRIVATE' and media.status='READY' and media.kind in ('IMAGE','VIDEO')
                  and (media.kind<>'VIDEO' or media.streaming_protocol='PROGRESSIVE')))
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final UserRepository users;

    @Transactional(readOnly = true)
    public ProfileType viewerProfile(UUID viewerId) {
        if (!active(viewerId)) throw new SoundConnectException(ErrorType.UNAUTHORIZED);
        Set<String> profiles = users.findExistingPersonalProfileRoleNames(viewerId);
        Set<String> roles = users.findRoleNamesByUserId(viewerId);
        if (profiles.size() != 1) throw new SoundConnectException(ErrorType.ANNOUNCEMENT_FORBIDDEN);
        String role = profiles.iterator().next();
        ProfileType profile;
        try { profile = ProfileType.valueOf(role.substring("ROLE_".length())); }
        catch (RuntimeException malformed) { throw new SoundConnectException(ErrorType.ANNOUNCEMENT_FORBIDDEN); }
        long personalRoleCount = roles.stream().filter(candidate -> Set.of("ROLE_MUSICIAN", "ROLE_LISTENER",
                "ROLE_VENUE", "ROLE_STUDIO", "ROLE_ORGANIZER", "ROLE_PRODUCER").contains(candidate)).count();
        if (!PROFILES.contains(profile) || !roles.contains(role) || personalRoleCount != 1) {
            throw new SoundConnectException(ErrorType.ANNOUNCEMENT_FORBIDDEN);
        }
        return profile;
    }

    @Transactional
    public void requireVisible(UUID viewerId, UUID announcementId) {
        lockActor(viewerId);
        ProfileType profile = viewerProfile(viewerId);
        if (announcementId == null || jdbc.queryForList(
                "select p.id from tlb_promotion p where p.id=:id and " + VISIBLE_SQL + " for share of p",
                Map.of("id", announcementId, "profile", profile.name(), "now", utcTimestamp(Instant.now())), UUID.class).isEmpty()) {
            throw notFound();
        }
    }

    /** Expected lifecycle/audience misses are contained before crossing a transactional proxy. */
    @Transactional
    public Optional<ProfileType> visibleProfile(UUID viewerId, UUID announcementId) {
        try {
            requireVisible(viewerId, announcementId);
            return Optional.of(viewerProfile(viewerId));
        } catch (SoundConnectException inaccessible) {
            if (Set.of(ErrorType.UNAUTHORIZED, ErrorType.ANNOUNCEMENT_FORBIDDEN,
                    ErrorType.ANNOUNCEMENT_NOT_FOUND).contains(inaccessible.getErrorType())) return Optional.empty();
            throw inaccessible;
        }
    }

    @Transactional(readOnly = true)
    public boolean canManage(UUID actorId) {
        if (actorId == null) return false;
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from tbl_user u where u.id=:id and u.status='ACTIVE' and u.email_verified and u.erased_at is null
                    and (exists(select 1 from user_permissions up join tbl_permissions permission on permission.id=up.permission_id
                            where up.user_id=u.id and permission.name='MANAGE_PROMOTIONS')
                        or exists(select 1 from user_roles ur join role_permissions rp on rp.role_id=ur.role_id
                            join tbl_permissions permission on permission.id=rp.permission_id
                            where ur.user_id=u.id and permission.name='MANAGE_PROMOTIONS')))
                """, Map.of("id", actorId), Boolean.class));
    }

    @Transactional
    public void requireManage(UUID actorId) {
        lockActor(actorId);
        if (!canManage(actorId)) throw new SoundConnectException(ErrorType.ANNOUNCEMENT_FORBIDDEN);
    }

    @Transactional
    public void requireManage(UUID actorId, UUID announcementId) {
        requireManage(actorId);
        if (announcementId == null || jdbc.queryForList("""
                select id from tlb_promotion where id=:id and placement='FEED' and type='ANNOUNCEMENT'
                    for share
                """, Map.of("id", announcementId), UUID.class).isEmpty()) throw notFound();
    }

    @Transactional
    public void requireUploadOwner(UUID actorId, UUID announcementId) {
        requireManage(actorId, announcementId);
        if (jdbc.queryForList("select id from tlb_promotion where id=:id and status<>'ARCHIVED'",
                Map.of("id", announcementId), UUID.class).isEmpty()) throw notFound();
    }

    @Transactional
    public void requireMediaAccess(UUID viewerId, UUID announcementId, UUID assetId) {
        if (canManage(viewerId)) {
            requireManage(viewerId, announcementId);
            // Archived management preserves its retained attachment, not arbitrary
            // abandoned uploads. Editing/uploading remains blocked separately.
            if (jdbc.queryForList("""
                    select id from tlb_promotion where id=:id and (status<>'ARCHIVED' or media_asset_id=:asset)
                    """, Map.of("id", announcementId, "asset", assetId), UUID.class).isEmpty()) throw notFound();
        } else {
            requireVisible(viewerId, announcementId);
            if (jdbc.queryForList("select id from tlb_promotion where id=:id and media_asset_id=:asset",
                    Map.of("id", announcementId, "asset", assetId), UUID.class).isEmpty()) throw notFound();
        }
    }

    private boolean active(UUID actorId) {
        return actorId != null && Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from tbl_user where id=:id and status='ACTIVE' and email_verified and erased_at is null)",
                Map.of("id", actorId), Boolean.class));
    }

    private void lockActor(UUID actorId) {
        if (actorId == null || jdbc.queryForList(
                "select id from tbl_user where id=:id and status='ACTIVE' and email_verified and erased_at is null for share",
                Map.of("id", actorId), UUID.class).isEmpty()) throw new SoundConnectException(ErrorType.UNAUTHORIZED);
    }

    public static Timestamp utcTimestamp(Instant time) {
        return Timestamp.valueOf(LocalDateTime.ofInstant(time, ZoneOffset.UTC));
    }

    public static SoundConnectException notFound() { return new SoundConnectException(ErrorType.ANNOUNCEMENT_NOT_FOUND); }
}
