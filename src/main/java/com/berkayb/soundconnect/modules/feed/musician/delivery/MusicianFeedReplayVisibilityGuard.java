package com.berkayb.soundconnect.modules.feed.musician.delivery;

import com.berkayb.soundconnect.modules.event.publication.EventProfilePublicationRepository;
import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedItemResponse;
import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedItemType;
import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedPageResponse;
import com.berkayb.soundconnect.modules.feed.musician.moderation.MusicianFeedRestrictionGuard;
import com.berkayb.soundconnect.modules.overthinking.dto.response.OverthinkingPostResponseDto;
import com.berkayb.soundconnect.modules.overthinking.service.OverthinkingPostService;
import com.berkayb.soundconnect.modules.promotion.announcement.AnnouncementReadService;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Time;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/** Rechecks current public visibility without rebuilding the ranked page. */
@Component
public class MusicianFeedReplayVisibilityGuard {
    private static final int MAX_ITEMS = 100;
    private static final ZoneId EVENT_ZONE = ZoneId.of("Europe/Istanbul");

    // Keep checks independent of ranking, candidate windows, following pools and
    // delivery dedup. A still-public item may have fallen outside a provider's
    // current window without becoming unsafe to replay.
    private static final String PUBLIC_PROFILES = """
            public_profiles as not materialized (
                select 'MUSICIAN'::text as profile_type, p.id as profile_id, p.user_id,
                       'MUSICIAN_PROFILE'::text as owner_type, p.id as owner_id
                from tbl_musician_profile p join tbl_user u on u.id=p.user_id
                where u.status='ACTIVE' and u.email_verified and u.erased_at is null
                union all
                select 'LISTENER',p.id,p.user_id,'LISTENER_PROFILE',p.id
                from "tbl_listener-profile" p join tbl_user u on u.id=p.user_id
                where u.status='ACTIVE' and u.email_verified and u.erased_at is null
                  and p.visibility_mode='STANDARD' and p.visibility_choice_completed
                  and exists(select 1 from user_roles ur join tbl_role r on r.id=ur.role_id
                      where ur.user_id=u.id and r.name='ROLE_LISTENER')
                  and not exists(select 1 from user_roles ur join tbl_role r on r.id=ur.role_id
                      where ur.user_id=u.id and r.name in ('ROLE_ADMIN','ROLE_OWNER','ROLE_MUSICIAN',
                          'ROLE_VENUE','ROLE_STUDIO','ROLE_ORGANIZER','ROLE_PRODUCER'))
                  and not exists(select 1 from tbl_musician_profile other where other.user_id=u.id)
                  and not exists(select 1 from tbl_studio_profile other where other.user_id=u.id)
                  and not exists(select 1 from tbl_organizer_profile other where other.user_id=u.id)
                  and not exists(select 1 from tbl_producer_profile other where other.user_id=u.id)
                  and not exists(select 1 from tbl_venues other where other.owner_id=u.id)
                union all
                select 'STUDIO',p.id,p.user_id,'STUDIO_PROFILE',p.id
                from tbl_studio_profile p join tbl_user u on u.id=p.user_id
                where u.status='ACTIVE' and u.email_verified and u.erased_at is null
                union all
                select 'VENUE',v.id,v.owner_id,'VENUE_PROFILE',p.id
                from tbl_venues v join tbl_user u on u.id=v.owner_id
                left join tbl_venue_profile p on p.venue_id=v.id
                where v.status='APPROVED' and u.status='ACTIVE' and u.email_verified and u.erased_at is null
                union all
                select 'BAND',b.id,m.user_id,'BAND',b.id
                from tbl_band b join tbl_band_member m on m.band_id=b.id and m.status='ACTIVE'
                join tbl_user u on u.id=m.user_id
                where u.status='ACTIVE' and u.email_verified and u.erased_at is null
            )
            """;

    private static final String PUBLIC_EVENTS = """
            public_events as not materialized (
                select event.id,event.musician_profile_id,event.band_id,event.venue_id,
                       event.profile_calendar_approved
                from tbl_event event
                join tbl_venues venue on venue.id=event.venue_id
                join tbl_user owner on owner.id=venue.owner_id
                join tbl_city city on city.id=venue.city_id
                join tbl_district district on district.id=venue.district_id and district.city_id=city.id
                join tbl_neighborhood neighborhood on neighborhood.id=venue.neighborhood_id
                    and neighborhood.district_id=district.id
                where event.event_origin='VENUE' and event.venue_calendar_approved
                  and event.performer_approval_status in ('APPROVED','NOT_REQUIRED')
                  and venue.status='APPROVED' and owner.status='ACTIVE' and owner.email_verified
                  and owner.erased_at is null and event.event_date is not null and event.start_time is not null
                  and (event.event_date>:today or (event.event_date=:today and %s>:nowSeconds))
                  and not exists(select 1 from event_performer_requests pending
                      where pending.event_id=event.id and pending.status='PENDING')
                  and (event.musician_profile_id is null or exists(select 1 from public_profiles p
                      where p.profile_type='MUSICIAN' and p.profile_id=event.musician_profile_id))
                  and (event.band_id is null or exists(select 1 from public_profiles p
                      where p.profile_type='BAND' and p.profile_id=event.band_id))
            )
            """.formatted(EventProfilePublicationRepository.SQL_START_SECONDS);

    private static final String REQUESTED_TARGETS = """
            requested as (select * from jsonb_to_recordset(cast(:checks as jsonb)) as requested(
                key text,kind text,id uuid,publication_id uuid,source_id uuid,
                author_type text,author_profile_id uuid,author_user_id uuid,
                musician_id uuid,band_id uuid))
            """;

    private static final String MEDIA_SQL = "with " + REQUESTED_TARGETS + "," + PUBLIC_PROFILES + """
            select distinct requested.key from requested
            join tbl_media_asset media on media.id=requested.id and media.status='READY'
                and media.visibility='PUBLIC'
                and coalesce(nullif(trim(media.playback_url),''),nullif(trim(media.source_url),'')) is not null
            join public_profiles publisher on publisher.owner_type=media.owner_type
                and publisher.owner_id=media.owner_id
            where (requested.author_profile_id is null or (publisher.profile_type=requested.author_type
                and publisher.profile_id=requested.author_profile_id and publisher.user_id=requested.author_user_id))
              and ((requested.kind='TRACK' and exists(select 1 from tbl_tracks track
                    where track.id=requested.publication_id and track.media_asset_id=media.id
                      and track.owner_type=media.owner_type and track.owner_id=media.owner_id))
                or (requested.kind='PROFILE_MEDIA' and exists(select 1 from tbl_profile_media attachment
                    where attachment.media_asset_id=media.id
                      and (requested.publication_id is null or attachment.id=requested.publication_id)
                      and attachment.profile_type=publisher.profile_type and attachment.profile_id=publisher.owner_id
                      and attachment.role in ('GALLERY','FEATURED_VIDEO','INTRO_VIDEO'))))
            """;

    private static final String EVENT_SQL = "with " + REQUESTED_TARGETS + "," + PUBLIC_PROFILES + "," + PUBLIC_EVENTS + """
            select distinct requested.key from requested join public_events event
              on event.id=case when requested.kind='EVENT_POST' then requested.source_id else requested.id end
            where event.musician_profile_id is not distinct from requested.musician_id
              and event.band_id is not distinct from requested.band_id
              and ((requested.kind='EVENT' and (requested.author_profile_id is null
                or (requested.author_type='VENUE' and requested.author_profile_id=event.venue_id)
                or (requested.author_type='BAND' and event.profile_calendar_approved
                    and requested.author_profile_id=event.band_id)
                or (requested.author_type='MUSICIAN' and (
                    (event.profile_calendar_approved and requested.author_profile_id=event.musician_profile_id)
                    or exists(select 1 from event_member_publications publication
                        where publication.event_id=event.id and publication.visible
                          and publication.musician_profile_id=requested.author_profile_id)))))
                or (requested.kind='EVENT_POST' and exists(
                    select 1 from tbl_event_audience_intent intent join public_profiles publisher
                      on publisher.profile_type='LISTENER' and publisher.user_id=intent.user_id
                    where intent.post_id=requested.id and intent.event_id=event.id
                      and intent.published_on_profile and intent.intent<>'NONE'
                      and (requested.author_profile_id is null or (publisher.profile_id=requested.author_profile_id
                          and publisher.user_id=requested.author_user_id)))))
            """;

    private static final String SHARES_SQL = "with " + REQUESTED_TARGETS + "," + PUBLIC_PROFILES + """
            select distinct requested.key from requested
            join tbl_overthinking_profile_share share on requested.kind='OVERTHINKING_PROFILE_SHARE'
                and share.id=requested.id and share.source_post_id=requested.source_id
            join tbl_overthinking_post source on source.id=share.source_post_id
            join public_profiles publisher on publisher.profile_type='LISTENER'
                and publisher.profile_id=share.listener_profile_id and publisher.user_id=share.owner_user_id
            where requested.author_profile_id is null or (publisher.profile_id=requested.author_profile_id
                and publisher.user_id=requested.author_user_id)
            union all
            select distinct requested.key from requested
            join tbl_table_group_profile_share share on requested.kind='TABLE_GROUP_POST'
                and share.id=requested.id and share.table_group_id=requested.source_id
            join tbl_table_group table_group on table_group.id=share.table_group_id
            join public_profiles publisher on publisher.profile_type='LISTENER'
                and publisher.profile_id=share.listener_profile_id and publisher.user_id=share.owner_user_id
            where (requested.author_profile_id is null or (publisher.profile_id=requested.author_profile_id
                and publisher.user_id=requested.author_user_id))
              and (share.final_source is not null or (not share.final_source_frozen
                  and table_group.status='ACTIVE' and table_group.expires_at>:now
                  and (table_group.owner_id=share.owner_user_id or exists(
                      select 1 from tbl_table_group_participants participant
                      where participant.table_group_id=table_group.id
                        and participant.user_id=share.owner_user_id and participant.status='ACCEPTED'))))
            """;

    private static final String COLLAB_SQL = "with " + REQUESTED_TARGETS + "," + PUBLIC_PROFILES + """
            select distinct requested.key from requested join tbl_collab listing on listing.id=requested.id
            join tbl_collab_actor actor on actor.id=listing.publisher_actor_id and actor.active
            join public_profiles publisher on publisher.profile_type=actor.profile_type
                and publisher.profile_id=actor.source_profile_id and publisher.user_id=listing.owner_user_id
            where listing.status='OPEN' and listing.published_at is not null
              and (listing.expires_at is null or listing.expires_at>:now)
              and actor.profile_type in ('MUSICIAN','BAND','VENUE','STUDIO')
              and publisher.profile_type=requested.author_type and publisher.profile_id=requested.author_profile_id
              and publisher.user_id=requested.author_user_id
              and (actor.profile_type<>'BAND' or exists(select 1 from tbl_band_member founder
                  where founder.band_id=actor.source_profile_id and founder.user_id=listing.owner_user_id
                    and founder.status='ACTIVE' and founder.band_role='FOUNDER'))
            """;

    private static final String IDENTITIES_SQL = "with " + PUBLIC_PROFILES + """
            ,requested as (select * from jsonb_to_recordset(cast(:identities as jsonb))
                as requested(key text,profile_type text,profile_id uuid,user_id uuid))
            select distinct requested.key from requested join public_profiles p
              on p.profile_type=requested.profile_type and p.profile_id=requested.profile_id and p.user_id=requested.user_id
            """;
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final OverthinkingPostService overthinking;
    private final MusicianFeedRestrictionGuard restrictions;
    private final AnnouncementReadService announcements;

    public MusicianFeedReplayVisibilityGuard(NamedParameterJdbcTemplate jdbc,
                                            ObjectMapper objectMapper,
                                            OverthinkingPostService overthinking,
                                            MusicianFeedRestrictionGuard restrictions) {
        this(jdbc, objectMapper, overthinking, restrictions, null);
    }

    @Autowired
    public MusicianFeedReplayVisibilityGuard(NamedParameterJdbcTemplate jdbc,
                                            ObjectMapper objectMapper,
                                            OverthinkingPostService overthinking,
                                            MusicianFeedRestrictionGuard restrictions,
                                            AnnouncementReadService announcements) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.overthinking = overthinking;
        this.restrictions = restrictions;
        this.announcements = announcements;
    }

    public void requireVisible(UUID viewerId, MusicianFeedPageResponse page, Instant now) {
        if (viewerId == null || page == null || now == null || page.items() == null
                || page.items().size() > MAX_ITEMS) throw invalid();
        List<Map<String, Object>> identities = new ArrayList<>();
        Map<String, List<Map<String, Object>>> targets = new LinkedHashMap<>();
        Map<UUID, List<JsonNode>> sources = new LinkedHashMap<>();
        List<Map<String, Object>> activities = new ArrayList<>();
        List<String> itemIds = new ArrayList<>();
        for (MusicianFeedItemResponse item : page.items()) {
            if (item == null || item.type() == null || item.id() == null || item.reason() == null
                    || item.payloadVersion() != 1 || !item.id().startsWith(item.type().name() + ":")) throw invalid();
            // No current campaign authority exists yet. A future sponsor adapter
            // must supply a current campaign check before paid cache replay opens.
            if (item.promotion() != null || item.type() == MusicianFeedItemType.SPONSORED) throw invalid();
            itemIds.add(item.id());
            addIdentity(identities, item.author());
            for (var actor : item.reason().actors()) addIdentity(identities, actor);
            JsonNode payload = objectMapper.valueToTree(item.payload());
            MusicianFeedItemType type = item.type();
            boolean activity = type == MusicianFeedItemType.ACTIVITY_FOLLOW
                    || type == MusicianFeedItemType.ACTIVITY_LIKE || type == MusicianFeedItemType.ACTIVITY_COMMENT;
            if (type != MusicianFeedItemType.PROFILE_COMPLETION && type != MusicianFeedItemType.ANNOUNCEMENT
                    && item.author() == null) throw invalid();
            if (activity) {
                addIdentity(identities, payload.path("actor"));
                try {
                    type = MusicianFeedItemType.valueOf(requiredText(payload, "targetItemType"));
                } catch (IllegalArgumentException invalidType) { throw invalid(); }
                payload = payload.path("targetPayload");
                if (type.name().startsWith("ACTIVITY_") || type == MusicianFeedItemType.PROFILE_COMPLETION
                        || type == MusicianFeedItemType.ANNOUNCEMENT
                        || type == MusicianFeedItemType.SPONSORED) throw invalid();
                addActivity(activities, item, item.type());
            } else if (item.reason().code() != null) {
                // Native cards retain social proof after the mixer combines a
                // like/follow story with the underlying publication.
                switch (item.reason().code()) {
                    case FOLLOWED_USER_LIKED -> addActivity(activities, item, MusicianFeedItemType.ACTIVITY_LIKE);
                    case FOLLOWED_USER_FOLLOWED -> addActivity(activities, item, MusicianFeedItemType.ACTIVITY_FOLLOW);
                    default -> { }
                }
            }
            var publisher = activity ? null : item.author();
            Map<String, Object> check = new LinkedHashMap<>();
            check.put("key", Integer.toString(itemIds.size()));
            check.put("kind", type.name());
            if (publisher != null) putPublisher(check, publisher.profileType(), publisher.profileId(), publisher.userId());
            switch (type) {
                case ANNOUNCEMENT -> {
                    UUID announcementId = id(payload, "id");
                    requireTarget(item, "ANNOUNCEMENT", announcementId);
                    if (!item.id().equals("ANNOUNCEMENT:" + announcementId) || announcements == null
                            || item.author() != null || !item.reason().actors().isEmpty()
                            || item.reason().code() != com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedReasonCode.PLATFORM_ANNOUNCEMENT) throw invalid();
                    try { announcements.requireVisible(viewerId, announcementId); }
                    catch (SoundConnectException noLongerVisible) { throw invalid(); }
                    continue;
                }
                case PROFILE_COMPLETION -> {
                    UUID profileId = itemId(item.id(), "PROFILE_COMPLETION:");
                    requireTarget(item, "PROFILE", profileId);
                    addIdentity(identities, "MUSICIAN", profileId, viewerId);
                    continue;
                }
                case PROFILE -> {
                    addIdentity(identities, payload);
                    requireTarget(item, "PROFILE", id(payload, "profileId"));
                    continue;
                }
                case TRACK -> {
                    check.put("id", id(payload, "mediaAssetId"));
                    check.put("publication_id", id(payload, "trackId"));
                    requireTarget(item, "MEDIA", (UUID) check.get("id"));
                    addTarget(targets, "MEDIA", check);
                }
                case PROFILE_MEDIA -> {
                    check.put("id", id(payload, "mediaAssetId"));
                    if (!activity) check.put("publication_id", itemId(item.id(), "PROFILE_MEDIA:"));
                    requireTarget(item, "MEDIA", (UUID) check.get("id"));
                    addTarget(targets, "MEDIA", check);
                }
                case EVENT, EVENT_PROFILE_SHARE -> {
                    JsonNode event = payload.path("event");
                    UUID eventId = id(event, "id");
                    check.put("musician_id", optionalId(event, "musicianProfileId"));
                    check.put("band_id", optionalId(event, "bandId"));
                    if (type == MusicianFeedItemType.EVENT_PROFILE_SHARE) {
                        check.put("kind", "EVENT_POST");
                        check.put("id", id(payload, "publicationId"));
                        check.put("source_id", eventId);
                        requireTarget(item, "EVENT_POST", (UUID) check.get("id"));
                    } else {
                        check.put("id", eventId);
                        requireTarget(item, "EVENT", eventId);
                    }
                    addTarget(targets, "EVENT", check);
                }
                case OVERTHINKING_PROFILE_SHARE, TABLEGROUP_PROFILE_SHARE -> {
                    UUID shareId = id(payload, "shareId");
                    JsonNode source = payload.path("source");
                    UUID sourceId = type == MusicianFeedItemType.OVERTHINKING_PROFILE_SHARE
                            ? id(source, "id") : firstId(source, "tableGroupId", "id");
                    check.put("id", shareId);
                    check.put("source_id", sourceId);
                    String targetType = type == MusicianFeedItemType.OVERTHINKING_PROFILE_SHARE
                            ? "OVERTHINKING_PROFILE_SHARE" : "TABLE_GROUP_POST";
                    check.put("kind", targetType);
                    requireTarget(item, targetType, shareId);
                    if (type == MusicianFeedItemType.OVERTHINKING_PROFILE_SHARE)
                        sources.computeIfAbsent(sourceId, ignored -> new ArrayList<>()).add(source);
                    addTarget(targets, "SHARE", check);
                }
                case COLLAB -> {
                    JsonNode listing = payload.path("listing");
                    JsonNode actor = listing.path("publisher");
                    UUID listingId = id(listing, "id");
                    putPublisher(check, requiredText(actor, "profileType"), id(actor, "sourceProfileId"),
                            id(actor, "contactUserId"));
                    check.put("id", listingId);
                    requireTarget(item, "COLLAB", listingId);
                    addTarget(targets, "COLLAB", check);
                }
                default -> throw invalid();
            }
        }
        if (identities.size() > MAX_ITEMS * 10 || activities.size() > MAX_ITEMS * 10) throw invalid();
        restrictions.requireUnrestricted(page.items());
        MapSqlParameterSource parameters = parameters(now).addValue("viewerId", viewerId);
        requireRows(IDENTITIES_SQL, "identities", identities, parameters);
        for (var entry : targets.entrySet()) {
            String sql = switch (entry.getKey()) {
                case "MEDIA" -> MEDIA_SQL;
                case "EVENT" -> EVENT_SQL;
                case "SHARE" -> SHARES_SQL;
                case "COLLAB" -> COLLAB_SQL;
                default -> throw invalid();
            };
            requireRows(sql, "checks", entry.getValue(), parameters);
        }
        requireCurrentActivities(activities, parameters);
        requireNotSuppressed(itemIds, identities, parameters);
        requireSourcePrivacy(viewerId, sources);
    }

    private void requireRows(String sql, String parameter, List<Map<String, Object>> expected,
                             MapSqlParameterSource parameters) {
        if (expected.isEmpty()) return;
        parameters.addValue(parameter, json(expected));
        Set<String> actual = new HashSet<>(jdbc.queryForList(sql, parameters, String.class));
        if (expected.stream().anyMatch(row -> !actual.contains(row.get("key")))) throw invalid();
    }

    private void requireSourcePrivacy(UUID viewerId, Map<UUID, List<JsonNode>> sources) {
        List<UUID> ids = new ArrayList<>(sources.keySet());
        for (int offset = 0; offset < ids.size(); offset += 50) {
            List<UUID> batch = ids.subList(offset, Math.min(ids.size(), offset + 50));
            Map<UUID, OverthinkingPostResponseDto> current = overthinking.getByIdsForViewer(viewerId, List.copyOf(batch));
            if (current == null) throw invalid();
            for (UUID sourceId : batch) {
                var dto = current.get(sourceId);
                if (dto == null) throw invalid();
                JsonNode visible = objectMapper.valueToTree(dto);
                for (JsonNode cached : sources.get(sourceId)) {
                    // Counts and reaction/reveal-request status may change while
                    // a retry retains its original response. Identity access may not.
                    if (cached.path("canViewAuthor").asBoolean(false)
                            && (!dto.canViewAuthor() || !Objects.equals(optionalId(cached, "authorId"), dto.authorId())))
                        throw invalid();
                    if (!Objects.equals(textOrNull(cached, "authorVisibilityMode"), textOrNull(visible, "authorVisibilityMode")))
                        throw invalid();
                    if (cached.path("canViewAuthor").asBoolean(false) && dto.authorId() == null) throw invalid();
                    if (!cached.path("canViewAuthor").asBoolean(false)
                            && (optionalId(cached, "authorId") != null || textOrNull(cached, "authorAvatarUrl") != null))
                        throw invalid();
                }
            }
        }
    }

    private void requireNotSuppressed(List<String> itemIds, List<Map<String, Object>> identities,
                                      MapSqlParameterSource parameters) {
        if (itemIds.isEmpty()) return;
        Set<SuppressionScope> scopes = new LinkedHashSet<>();
        for (String itemId : itemIds) {
            scopes.add(new SuppressionScope("HIDE", "ITEM:" + itemId));
            scopes.add(new SuppressionScope("REPORT", "ITEM:" + itemId));
        }
        for (var identity : identities) {
            scopes.add(new SuppressionScope("MUTE_AUTHOR", "AUTHOR:"
                    + identity.get("profile_type") + ":" + identity.get("profile_id")));
        }
        List<String> requested = new ArrayList<>();
        for (var scope : scopes) {
            int index = requested.size();
            requested.add("(:suppressionAction" + index + ",:suppressionScope" + index + ")");
            parameters.addValue("suppressionAction" + index, scope.action())
                    .addValue("suppressionScope" + index, scope.key());
        }
        // Probe the unique viewer/action/scope index for this cached page.
        // A viewer's lifetime preference history never needs to be hydrated
        // or hashed to decide whether one of these identities is suppressed.
        Boolean suppressed = jdbc.queryForObject("""
                select exists(
                  select 1 from (values %s) requested(action,scope_key)
                  join lateral (
                    select 1 from tbl_musician_feed_feedback feedback
                    where feedback.viewer_user_id=:viewerId and feedback.action=requested.action
                      and feedback.scope_key=requested.scope_key limit 1
                  ) matched on true)
                """.formatted(String.join(",", requested)), parameters, Boolean.class);
        if (!Boolean.FALSE.equals(suppressed)) throw invalid();
    }

    private record SuppressionScope(String action, String key) { }

    private void addActivity(List<Map<String, Object>> activities, MusicianFeedItemResponse item,
                             MusicianFeedItemType actionType) {
        if (item.target() == null) throw invalid();
        List<MusicianFeedItemResponse.Author> actors = item.reason().actors();
        if (actors.isEmpty()) throw invalid();
        boolean nativeStory = !item.type().name().startsWith("ACTIVITY_");
        int actorIndex = 0;
        for (var actor : actors) {
            if (actor == null || actor.userId() == null) throw invalid();
            // Mixed reasons do not tag each actor's origin. Preserve known
            // publication credit. The mixer puts primary LIKE/FOLLOW actors
            // first, so that first actor must retain the actual action even
            // when they also own the underlying publication.
            boolean publisherCredit = nativeStory && item.author() != null
                    && Objects.equals(actor.userId(), item.author().userId())
                    && Objects.equals(actor.profileId(), item.author().profileId())
                    && Objects.equals(actor.profileType(), item.author().profileType());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", Integer.toString(activities.size()));
            row.put("kind", actionType.name());
            row.put("actor_id", actor.userId());
            row.put("actor_profile_type", actor.profileType());
            row.put("actor_profile_id", actor.profileId());
            row.put("target_type", item.target().type());
            row.put("target_id", item.target().id());
            row.put("native_story", nativeStory);
            row.put("primary_proof", actorIndex++ == 0);
            row.put("publisher_credit", publisherCredit);
            if (item.type() == MusicianFeedItemType.EVENT) row.put("event_id", item.target().id());
            if (actionType == MusicianFeedItemType.ACTIVITY_COMMENT)
                row.put("comment_id", itemId(item.id(), "ACTIVITY_COMMENT:"));
            activities.add(row);
        }
    }

    private void requireCurrentActivities(List<Map<String, Object>> activities, MapSqlParameterSource parameters) {
        requireRows("""
                with requested as (select * from jsonb_to_recordset(cast(:activities as jsonb))
                  as r(key text,kind text,actor_id uuid,actor_profile_type text,
                    actor_profile_id uuid,target_type text,target_id uuid,comment_id uuid,
                    native_story boolean,primary_proof boolean,publisher_credit boolean,event_id uuid)),
                proofs as (
                  select r.*,
                  ((r.kind='ACTIVITY_LIKE' and exists(select 1 from tbl_like l
                    where l.user_id=r.actor_id and l.target_type=r.target_type and l.target_id=r.target_id))
                  or (r.kind='ACTIVITY_COMMENT' and exists(select 1 from tbl_comment c
                    where c.id=r.comment_id and c.user_id=r.actor_id and c.target_type=r.target_type
                      and c.target_id=r.target_id and not c.is_deleted))
                  or (r.kind='ACTIVITY_FOLLOW' and (
                    exists(select 1 from tbl_band_follow f where f.follower_id=r.actor_id and f.band_id=r.target_id)
                    or exists(select 1 from tbl_follow f where f.follower_id=r.actor_id and f.following_id in (
                      select user_id from tbl_musician_profile where id=r.target_id
                      union all select user_id from "tbl_listener-profile" where id=r.target_id
                      union all select user_id from tbl_studio_profile where id=r.target_id
                      union all select owner_id from tbl_venues where id=r.target_id))))) as current_action,
                  (r.publisher_credit or (r.native_story and r.event_id is not null
                    and r.actor_profile_type='VENUE' and exists(
                      select 1 from tbl_event event join tbl_venues venue on venue.id=event.venue_id
                      where event.id=r.event_id and venue.id=r.actor_profile_id
                        and venue.owner_id=r.actor_id))) as publication_credit
                  from requested r
                )
                select r.key from proofs r where
                  (not r.native_story and r.current_action)
                  or (r.native_story and ((r.primary_proof and r.current_action)
                    or (not r.primary_proof and (r.current_action or r.publication_credit))))
                """, "activities", activities, parameters);
    }

    private void addIdentity(List<Map<String, Object>> identities, MusicianFeedItemResponse.Author actor) {
        if (actor != null) addIdentity(identities, actor.profileType(), actor.profileId(), actor.userId());
    }

    private void addIdentity(List<Map<String, Object>> identities, JsonNode actor) {
        if (actor == null || actor.isNull() || actor.isMissingNode()) throw invalid();
        addIdentity(identities, requiredText(actor, "profileType"), id(actor, "profileId"), id(actor, "userId"));
    }

    private void addIdentity(List<Map<String, Object>> identities, String type, UUID profileId, UUID userId) {
        if (type == null || profileId == null || userId == null) throw invalid();
        identities.add(Map.of("key", Integer.toString(identities.size()), "profile_type", type,
                "profile_id", profileId, "user_id", userId));
    }

    private void putPublisher(Map<String, Object> check, String type, UUID profileId, UUID userId) {
        if (type == null || profileId == null || userId == null) throw invalid();
        check.put("author_type", type);
        check.put("author_profile_id", profileId);
        check.put("author_user_id", userId);
    }

    private void addTarget(Map<String, List<Map<String, Object>>> targets, String family, Map<String, Object> check) {
        targets.computeIfAbsent(family, ignored -> new ArrayList<>()).add(check);
    }

    private void requireTarget(MusicianFeedItemResponse item, String type, UUID id) {
        if (item.target() == null || !type.equals(item.target().type()) || !id.equals(item.target().id())) throw invalid();
        if (item.engagement() != null && (!type.equals(item.engagement().targetType())
                || !id.equals(item.engagement().targetId()))) throw invalid();
    }

    private UUID id(JsonNode node, String field) {
        UUID value = optionalId(node, field);
        if (value == null) throw invalid();
        return value;
    }

    private UUID optionalId(JsonNode node, String field) {
        String value = textOrNull(node, field);
        if (value == null) return null;
        try { return UUID.fromString(value); }
        catch (IllegalArgumentException malformed) { throw invalid(); }
    }

    private UUID firstId(JsonNode node, String first, String second) {
        UUID value = optionalId(node, first);
        return value == null ? id(node, second) : value;
    }

    private UUID itemId(String value, String prefix) {
        if (!value.startsWith(prefix)) throw invalid();
        try { return UUID.fromString(value.substring(prefix.length())); }
        catch (IllegalArgumentException malformed) { throw invalid(); }
    }

    private String requiredText(JsonNode node, String field) {
        String value = textOrNull(node, field);
        if (value == null) throw invalid();
        return value;
    }

    private String textOrNull(JsonNode node, String field) {
        if (node == null || !node.path(field).isTextual()) return null;
        String value = node.path(field).asText();
        return value.isBlank() ? null : value;
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException malformed) { throw invalid(); }
    }

    private MapSqlParameterSource parameters(Instant now) {
        ZonedDateTime local = now.atZone(EVENT_ZONE);
        LocalTime midnight = Instant.ofEpochMilli(Time.valueOf(LocalTime.MIDNIGHT).getTime())
                .atZone(ZoneOffset.UTC).toLocalTime();
        return new MapSqlParameterSource().addValue("now", Timestamp.from(now))
                .addValue("today", local.toLocalDate()).addValue("nowSeconds", local.toLocalTime().toSecondOfDay())
                .addValue("storageMidnight", midnight);
    }

    private SoundConnectException invalid() { return new SoundConnectException(ErrorType.MUSICIAN_FEED_CURSOR_INVALID); }
}
