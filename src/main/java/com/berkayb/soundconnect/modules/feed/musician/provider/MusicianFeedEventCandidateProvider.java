package com.berkayb.soundconnect.modules.feed.musician.provider;

import com.berkayb.soundconnect.modules.event.dto.response.EventResponseDto;
import com.berkayb.soundconnect.modules.event.enums.*;
import com.berkayb.soundconnect.modules.event.publication.EventProfilePublicationRepository;
import com.berkayb.soundconnect.modules.event.support.EventShareUrlBuilder;
import com.berkayb.soundconnect.modules.feed.musician.api.*;
import com.berkayb.soundconnect.modules.feed.musician.candidate.*;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

@Component
public class MusicianFeedEventCandidateProvider implements MusicianFeedCandidateProvider {
    private static final ZoneId EVENT_ZONE = ZoneId.of("Europe/Istanbul");
    private static final String SQL = """
            select event.id, event.title, event.description, event.event_date,
                   %s as normalized_start_seconds,
                   case when event.end_time is null then null else %s end as normalized_end_seconds,
                   event.event_origin, event.venue_approval_status, event.venue_calendar_approved,
                   event.organizer_user_id, event.created_at,
                   coalesce(poster.playback_url, poster.source_url, event.poster_image) as poster_image,
                   venue.id as venue_id, venue.name as venue_name, city.name as venue_city,
                   district.name as venue_district, neighborhood.name as venue_neighborhood,
                   musician.id as musician_profile_id, musician_user.user_name as musician_username,
                   musician.stage_name as musician_stage_name, band.id as band_id, band.name as band_name,
                   event.manual_performer_name,
                   case
                     when event.profile_calendar_approved and musician_user.id is not null then musician_user.id
                     when member_publication.author_user_id is not null then member_publication.author_user_id
                     when event.profile_calendar_approved and band_actor.user_id is not null then band_actor.user_id
                     else venue_owner.id end as author_user_id,
                   case
                     when event.profile_calendar_approved and musician_user.id is not null then musician.id
                     when member_publication.author_profile_id is not null then member_publication.author_profile_id
                     when event.profile_calendar_approved and band_actor.user_id is not null then band.id
                     else venue.id end as author_profile_id,
                   case
                     when event.profile_calendar_approved and musician_user.id is not null then 'MUSICIAN'
                     when member_publication.author_profile_id is not null then 'MUSICIAN'
                     when event.profile_calendar_approved and band_actor.user_id is not null then 'BAND'
                     else 'VENUE' end as author_profile_type,
                   case
                     when event.profile_calendar_approved and musician_user.id is not null then musician_user.user_name
                     when member_publication.author_user_id is not null then member_publication.username
                     when event.profile_calendar_approved and band_actor.user_id is not null then band_actor.username
                     else venue_owner.user_name end as author_username,
                   case
                     when event.profile_calendar_approved and musician_user.id is not null
                       then coalesce(musician.stage_name, musician.name, musician_user.user_name)
                     when member_publication.author_user_id is not null
                       then coalesce(member_publication.stage_name, member_publication.name, member_publication.username)
                     when event.profile_calendar_approved and band_actor.user_id is not null then band.name
                     else venue.name end as author_display_name,
                   case
                     when event.profile_calendar_approved and musician_user.id is not null
                       then coalesce(musician_avatar.playback_url, musician_avatar.source_url, musician_user.profile_picture)
                     when member_publication.author_user_id is not null then member_publication.avatar_url
                     when event.profile_calendar_approved and band_actor.user_id is not null
                       then coalesce(band_avatar.playback_url, band_avatar.source_url)
                     else coalesce(venue_avatar.playback_url, venue_avatar.source_url, venue_owner.profile_picture)
                     end as author_avatar_url,
                   case
                     when event.profile_calendar_approved and musician_user.id is not null then musician_follow.id is not null
                     when member_publication.author_user_id is not null then member_publication.followed_by_viewer
                     when event.profile_calendar_approved and band_actor.user_id is not null then band_follow.id is not null
                     else venue_follow.id is not null end as followed_by_viewer,
                   ((event.profile_calendar_approved and musician_follow.id is not null)
                     or member_publication.followed_by_viewer
                     or (event.profile_calendar_approved and band_follow.id is not null)
                     or venue_follow.id is not null) as socially_followed,
                   (:hasCity and venue.city_id=:cityId) as city_match,
                   venue_owner.id as venue_author_user_id, venue.id as venue_author_profile_id,
                   venue_owner.user_name as venue_author_username, venue.name as venue_author_display_name,
                   coalesce(venue_avatar.playback_url, venue_avatar.source_url,
                            venue_avatar.thumbnail_url, venue_owner.profile_picture) as venue_author_avatar_url,
                   (venue_follow.id is not null) as venue_followed_by_viewer,
                   (event.organizer_user_id=:viewerId or
                     (event.profile_calendar_approved and musician_user.id=:viewerId) or
                     member_publication.author_user_id=:viewerId or
                     exists(select 1 from tbl_band_member own_member where own_member.band_id=band.id
                            and own_member.user_id=:viewerId and own_member.status='ACTIVE')) as owned_by_viewer,
                   (select count(*) from tbl_like likes where likes.target_type='EVENT' and likes.target_id=event.id) as like_count,
                   (select count(*) from tbl_comment comments where comments.target_type='EVENT'
                       and comments.target_id=event.id and not comments.is_deleted) as comment_count,
                   exists(select 1 from tbl_like mine where mine.user_id=:viewerId
                       and mine.target_type='EVENT' and mine.target_id=event.id) as liked_by_me
            from tbl_event event
            join tbl_venues venue on venue.id=event.venue_id
            join tbl_user venue_owner on venue_owner.id=venue.owner_id
            join tbl_city city on city.id=venue.city_id
            join tbl_district district on district.id=venue.district_id and district.city_id=city.id
            join tbl_neighborhood neighborhood on neighborhood.id=venue.neighborhood_id
                and neighborhood.district_id=district.id
            left join tbl_venue_profile venue_profile on venue_profile.venue_id=venue.id
            left join tbl_musician_profile musician on musician.id=event.musician_profile_id
            left join tbl_user musician_user on musician_user.id=musician.user_id
                and musician_user.status='ACTIVE' and musician_user.email_verified
                and musician_user.erased_at is null
            left join tbl_band band on band.id=event.band_id
            left join lateral (
                select member.user_id, account.user_name as username
                from tbl_band_member member join tbl_user account on account.id=member.user_id
                    and account.status='ACTIVE' and account.email_verified and account.erased_at is null
                where member.band_id=band.id and member.status='ACTIVE'
                order by case when member.band_role='FOUNDER' then 0 else 1 end, member.id limit 1
            ) band_actor on true
            left join lateral (
                select profile.user_id as author_user_id, profile.id as author_profile_id,
                       account.user_name as username, profile.stage_name, profile.name,
                       coalesce(avatar.playback_url, avatar.source_url, account.profile_picture) as avatar_url,
                       (member_follow.id is not null) as followed_by_viewer
                from event_member_publications publication
                join tbl_musician_profile profile on profile.id=publication.musician_profile_id
                join tbl_user account on account.id=profile.user_id and account.status='ACTIVE'
                    and account.email_verified and account.erased_at is null
                left join tbl_media_asset avatar on avatar.id=profile.profile_picture_media_id
                    and avatar.status='READY' and avatar.visibility='PUBLIC'
                left join tbl_follow member_follow on member_follow.follower_id=:viewerId
                    and member_follow.following_id=profile.user_id
                where publication.event_id=event.id and publication.visible
                order by (member_follow.id is not null) desc, profile.id limit 1
            ) member_publication on true
            left join tbl_media_asset poster on poster.id::text=event.poster_image
                and poster.status='READY' and poster.visibility='PUBLIC'
            left join tbl_media_asset musician_avatar on musician_avatar.id=musician.profile_picture_media_id
                and musician_avatar.status='READY' and musician_avatar.visibility='PUBLIC'
            left join tbl_media_asset band_avatar on band_avatar.id=band.profile_picture_media_id
                and band_avatar.status='READY' and band_avatar.visibility='PUBLIC'
            left join tbl_media_asset venue_avatar on venue_avatar.id=venue_profile.profile_picture_media_id
                and venue_avatar.status='READY' and venue_avatar.visibility='PUBLIC'
            left join tbl_follow musician_follow on musician_follow.follower_id=:viewerId
                and musician_follow.following_id=musician_user.id
            left join tbl_follow venue_follow on venue_follow.follower_id=:viewerId
                and venue_follow.following_id=venue_owner.id
            left join tbl_band_follow band_follow on band_follow.follower_id=:viewerId and band_follow.band_id=band.id
            where event.event_origin='VENUE' and event.venue_calendar_approved
              and event.performer_approval_status in ('APPROVED','NOT_REQUIRED')
              and venue.status='APPROVED' and venue_owner.status='ACTIVE'
              and venue_owner.email_verified and venue_owner.erased_at is null
              and not (event.organizer_user_id=:viewerId
                    or (event.profile_calendar_approved and musician_user.id=:viewerId)
                    or member_publication.author_user_id=:viewerId
                    or exists(select 1 from tbl_band_member own_member
                        where own_member.band_id=band.id and own_member.user_id=:viewerId
                          and own_member.status='ACTIVE'))
              and not exists(select 1 from tbl_musician_feed_feedback feedback
                  where feedback.viewer_user_id=:viewerId and (
                    (feedback.action in ('HIDE','REPORT')
                     and feedback.item_id='EVENT:' || event.id::text)
                    or (feedback.action='MUTE_AUTHOR'
                        and feedback.author_profile_type=(case
                          when event.profile_calendar_approved and musician_user.id is not null then 'MUSICIAN'
                          when member_publication.author_profile_id is not null then 'MUSICIAN'
                          when event.profile_calendar_approved and band_actor.user_id is not null then 'BAND'
                          else 'VENUE' end)
                        and feedback.author_profile_id=(case
                          when event.profile_calendar_approved and musician_user.id is not null then musician.id
                          when member_publication.author_profile_id is not null then member_publication.author_profile_id
                          when event.profile_calendar_approved and band_actor.user_id is not null then band.id
                          else venue.id end))))
              and event.event_date is not null and event.start_time is not null
              and (event.event_date>:today or (event.event_date=:today and %s>:nowSeconds))
              and event.created_at<=:anchor
              and not exists(select 1 from event_performer_requests pending
                    where pending.event_id=event.id and pending.status='PENDING')
              and not exists(select 1 from tbl_musician_feed_delivery delivered
                  where delivered.viewer_user_id=:viewerId and delivered.feed_session_id=:feedSessionId
                    and (delivered.item_id='EVENT:' || event.id::text
                      or (delivered.item_type<>'ACTIVITY_COMMENT' and delivered.target_type='EVENT'
                          and delivered.target_id=event.id)))
              and (
                (:pool='FOLLOWING' and ((event.profile_calendar_approved and musician_follow.id is not null)
                    or coalesce(member_publication.followed_by_viewer,false)
                    or (event.profile_calendar_approved and band_follow.id is not null)
                    or venue_follow.id is not null))
                or (:pool='RELEVANT' and not ((event.profile_calendar_approved and musician_follow.id is not null)
                    or coalesce(member_publication.followed_by_viewer,false)
                    or (event.profile_calendar_approved and band_follow.id is not null)
                    or venue_follow.id is not null)
                    and :hasCity and venue.city_id=:cityId)
                or (:pool='GENERAL' and not ((event.profile_calendar_approved and musician_follow.id is not null)
                    or coalesce(member_publication.followed_by_viewer,false)
                    or (event.profile_calendar_approved and band_follow.id is not null)
                    or venue_follow.id is not null)
                    and not (:hasCity and venue.city_id=:cityId))
              )
            order by event.event_date, %s, event.id
            limit :limit
            """.formatted(
            EventProfilePublicationRepository.SQL_START_SECONDS,
            EventProfilePublicationRepository.SQL_END_SECONDS,
            EventProfilePublicationRepository.SQL_START_SECONDS,
            EventProfilePublicationRepository.SQL_START_SECONDS);

    private final NamedParameterJdbcTemplate jdbc;
    private final EventShareUrlBuilder shareUrls;

    public MusicianFeedEventCandidateProvider(NamedParameterJdbcTemplate jdbc, EventShareUrlBuilder shareUrls) {
        this.jdbc = jdbc;
        this.shareUrls = shareUrls;
    }

    @Override public String providerId() { return "future-events"; }
    @Override public Set<MusicianFeedItemType> supportedTypes() { return Set.of(MusicianFeedItemType.EVENT); }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, timeout = 5)
    public List<MusicianFeedCandidate> findCandidates(MusicianFeedCandidateRequest request) {
        if (!request.supportedTypes().contains(MusicianFeedItemType.EVENT)) return List.of();
        ZonedDateTime now = request.readAt().atZone(EVENT_ZONE);
        UUID cityId = request.personalization().opportunityCityId();
        var parameters = new MapSqlParameterSource()
                .addValue("viewerId", request.viewerUserId())
                .addValue("feedSessionId", request.feedSessionId())
                .addValue("cityId", cityId == null ? new UUID(0, 0) : cityId)
                .addValue("hasCity", cityId != null)
                .addValue("anchor", MusicianFeedJdbcSupport.timestamp(request.anchor()))
                .addValue("today", now.toLocalDate())
                .addValue("nowSeconds", now.toLocalTime().toSecondOfDay())
                .addValue("storageMidnight",
                        MusicianFeedJdbcSupport.hibernateUtcStorageTime(LocalTime.MIDNIGHT));
        int followingLimit = request.limit() * 6 / 10;
        int relevantLimit = request.limit() * 3 / 10;
        int generalLimit = request.limit() - followingLimit - relevantLimit;
        List<MusicianFeedCandidate> result = new ArrayList<>(request.limit());
        if (followingLimit > 0) result.addAll(jdbc.query(SQL, parameters.addValue("pool", "FOLLOWING")
                .addValue("limit", followingLimit), this::candidate));
        if (relevantLimit > 0) result.addAll(jdbc.query(SQL, parameters.addValue("pool", "RELEVANT")
                .addValue("limit", relevantLimit), this::candidate));
        if (generalLimit > 0) result.addAll(jdbc.query(SQL, parameters.addValue("pool", "GENERAL")
                .addValue("limit", generalLimit), this::candidate));
        return List.copyOf(result);
    }

    private MusicianFeedCandidate candidate(java.sql.ResultSet row, int index) throws java.sql.SQLException {
            UUID eventId = MusicianFeedJdbcSupport.uuid(row, "id");
            var author = MusicianFeedJdbcSupport.author(row);
            var venueAuthor = new MusicianFeedItemResponse.Author(
                    MusicianFeedJdbcSupport.uuid(row, "venue_author_user_id"),
                    MusicianFeedJdbcSupport.uuid(row, "venue_author_profile_id"), "VENUE",
                    row.getString("venue_author_username"), row.getString("venue_author_display_name"),
                    row.getString("venue_author_avatar_url"), row.getBoolean("venue_followed_by_viewer"));
            UUID musicianId = MusicianFeedJdbcSupport.uuid(row, "musician_profile_id");
            UUID bandId = MusicianFeedJdbcSupport.uuid(row, "band_id");
            String manualName = row.getString("manual_performer_name");
            PerformerType performerType = bandId != null ? PerformerType.BAND
                    : musicianId != null ? PerformerType.MUSICIAN
                    : manualName != null && !manualName.isBlank() ? PerformerType.MANUAL : null;
            String performerName = bandId != null ? row.getString("band_name")
                    : musicianId != null ? firstText(row.getString("musician_stage_name"),
                    row.getString("musician_username")) : firstText(manualName, "Belirtilmemiş");
            EventResponseDto event = new EventResponseDto(eventId, row.getString("title"),
                    row.getString("poster_image"), performerName, musicianId, bandId, performerType, Set.of(),
                    MusicianFeedJdbcSupport.uuid(row, "venue_id"), row.getString("venue_name"),
                    row.getString("venue_city"), row.getString("venue_district"),
                    row.getString("venue_neighborhood"), row.getObject("event_date", LocalDate.class),
                    MusicianFeedJdbcSupport.normalizedTime(row, "normalized_start_seconds"),
                    MusicianFeedJdbcSupport.normalizedTime(row, "normalized_end_seconds"),
                    row.getString("description"), shareUrls.buildEventShareUrl(eventId),
                    EventOrigin.valueOf(row.getString("event_origin")),
                    EventVenueApprovalStatus.valueOf(row.getString("venue_approval_status")),
                    row.getBoolean("venue_calendar_approved"));
            List<MusicianFeedItemResponse.Author> reasonActors = new ArrayList<>();
            if (author.followedByViewer()) reasonActors.add(author);
            if (venueAuthor.followedByViewer() && !venueAuthor.userId().equals(author.userId())) {
                reasonActors.add(venueAuthor);
            }
            boolean sociallyFollowed = row.getBoolean("socially_followed");
            boolean cityMatch = row.getBoolean("city_match");
            var reason = new MusicianFeedItemResponse.Reason(
                    sociallyFollowed ? MusicianFeedReasonCode.FOLLOWING_PUBLICATION
                            : cityMatch ? MusicianFeedReasonCode.CITY_MATCH : MusicianFeedReasonCode.DISCOVERY,
                    reasonActors, 0);
            return new MusicianFeedCandidate("EVENT:" + eventId, MusicianFeedItemType.EVENT, 1,
                    MusicianFeedJdbcSupport.instant(row, "created_at"),
                    reason,
                    author, new MusicianFeedItemResponse.Target("EVENT", eventId),
                    MusicianFeedJdbcSupport.engagement(row, "EVENT", eventId), null,
                    MusicianFeedJdbcSupport.standardFeedback(), new MusicianFeedPayloads.Event(event, null, null),
                    sociallyFollowed ? 880_000L : 260_000L,
                    cityMatch ? 110_000 : 0, sociallyFollowed ? MusicianFeedLane.FOLLOWING
                            : cityMatch ? MusicianFeedLane.RELEVANT_OPPORTUNITY
                            : MusicianFeedLane.GENERAL_DISCOVERY, row.getBoolean("owned_by_viewer"));
    }

    private static String firstText(String... values) {
        return Arrays.stream(values).filter(value -> value != null && !value.isBlank()).findFirst().orElse(null);
    }
}
