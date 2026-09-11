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
public class MusicianFeedListenerEventShareCandidateProvider implements MusicianFeedCandidateProvider {
    private static final ZoneId EVENT_ZONE = ZoneId.of("Europe/Istanbul");
    private static final String SQL = """
            select intent.post_id, intent.note, intent.published_at,
                   event.id as event_id, event.title, event.description, event.event_date,
                   %s as normalized_start_seconds,
                   case when event.end_time is null then null else %s end as normalized_end_seconds,
                   event.event_origin,
                   event.venue_approval_status, event.venue_calendar_approved,
                   coalesce(poster.playback_url, poster.source_url, event.poster_image) as poster_image,
                   venue.id as venue_id, venue.name as venue_name, city.name as venue_city,
                   district.name as venue_district, neighborhood.name as venue_neighborhood,
                   musician.id as musician_profile_id, musician_user.user_name as musician_username,
                   musician.stage_name as musician_stage_name, band.id as band_id, band.name as band_name,
                   event.manual_performer_name,
                   account.id as author_user_id, listener.id as author_profile_id,
                   'LISTENER' as author_profile_type, account.user_name as author_username,
                   coalesce(listener.name, account.user_name) as author_display_name,
                   coalesce(avatar.playback_url, avatar.source_url, avatar.thumbnail_url, account.profile_picture) as author_avatar_url,
                   true as followed_by_viewer, (account.id=:viewerId) as owned_by_viewer,
                   (select count(*) from tbl_like likes where likes.target_type='EVENT_POST'
                       and likes.target_id=intent.post_id) as like_count,
                   (select count(*) from tbl_comment comments where comments.target_type='EVENT_POST'
                       and comments.target_id=intent.post_id and not comments.is_deleted) as comment_count,
                   exists(select 1 from tbl_like mine where mine.user_id=:viewerId
                       and mine.target_type='EVENT_POST' and mine.target_id=intent.post_id) as liked_by_me
            from tbl_event_audience_intent intent
            join tbl_user account on account.id=intent.user_id
            join \"tbl_listener-profile\" listener on listener.user_id=account.id
                and listener.visibility_mode='STANDARD' and listener.visibility_choice_completed
            join tbl_follow following on following.follower_id=:viewerId and following.following_id=account.id
            join tbl_event event on event.id=intent.event_id
            join tbl_venues venue on venue.id=event.venue_id
            join tbl_user venue_owner on venue_owner.id=venue.owner_id
            join tbl_city city on city.id=venue.city_id
            join tbl_district district on district.id=venue.district_id and district.city_id=city.id
            join tbl_neighborhood neighborhood on neighborhood.id=venue.neighborhood_id
                and neighborhood.district_id=district.id
            left join tbl_musician_profile musician on musician.id=event.musician_profile_id
            left join tbl_user musician_user on musician_user.id=musician.user_id
                and musician_user.status='ACTIVE' and musician_user.email_verified
                and musician_user.erased_at is null
            left join tbl_band band on band.id=event.band_id
            left join tbl_media_asset poster on poster.id::text=event.poster_image
                and poster.status='READY' and poster.visibility='PUBLIC'
            left join tbl_media_asset avatar on avatar.id=listener.profile_picture_media_id
                and avatar.status='READY' and avatar.visibility='PUBLIC'
            where intent.published_on_profile and intent.intent<>'NONE' and intent.post_id is not null
              and intent.published_at<=:anchor and account.id<>:viewerId
              and account.status='ACTIVE' and account.email_verified and account.erased_at is null
              and exists(select 1 from user_roles membership join tbl_role role on role.id=membership.role_id
                  where membership.user_id=account.id and role.name='ROLE_LISTENER')
              and not exists(select 1 from user_roles membership join tbl_role role on role.id=membership.role_id
                  where membership.user_id=account.id and role.name in ('ROLE_ADMIN','ROLE_OWNER','ROLE_MUSICIAN',
                      'ROLE_VENUE','ROLE_STUDIO','ROLE_ORGANIZER','ROLE_PRODUCER'))
              and not exists(select 1 from tbl_musician_profile profile where profile.user_id=account.id)
              and not exists(select 1 from tbl_studio_profile profile where profile.user_id=account.id)
              and not exists(select 1 from tbl_organizer_profile profile where profile.user_id=account.id)
              and not exists(select 1 from tbl_producer_profile profile where profile.user_id=account.id)
              and not exists(select 1 from tbl_venues owned_venue where owned_venue.owner_id=account.id)
              and event.event_origin='VENUE' and event.venue_calendar_approved
              and event.performer_approval_status in ('APPROVED','NOT_REQUIRED')
              and (event.musician_profile_id is null or musician_user.id is not null)
              and (event.band_id is null or exists(select 1 from tbl_band_member eligible_member
                  join tbl_user member_account on member_account.id=eligible_member.user_id
                    and member_account.status='ACTIVE' and member_account.email_verified
                    and member_account.erased_at is null
                  where eligible_member.band_id=event.band_id and eligible_member.status='ACTIVE'))
              and venue.status='APPROVED' and venue_owner.status='ACTIVE'
              and venue_owner.email_verified and venue_owner.erased_at is null
              and (event.event_date>:today or (event.event_date=:today and %s>:nowSeconds))
              and not exists(select 1 from event_performer_requests pending
                    where pending.event_id=event.id and pending.status='PENDING')
              and not exists(select 1 from tbl_musician_feed_feedback feedback
                  where feedback.viewer_user_id=:viewerId and (
                    (feedback.action in ('HIDE','REPORT')
                     and feedback.item_id='EVENT_PROFILE_SHARE:' || intent.post_id::text)
                    or (feedback.action='MUTE_AUTHOR' and feedback.author_profile_type='LISTENER'
                        and feedback.author_profile_id=listener.id)))
              and not exists(select 1 from tbl_musician_feed_delivery delivered
                  where delivered.viewer_user_id=:viewerId and delivered.feed_session_id=:feedSessionId
                    and (delivered.item_id='EVENT_PROFILE_SHARE:' || intent.post_id::text
                      or (delivered.item_type<>'ACTIVITY_COMMENT'
                          and delivered.target_type='EVENT_POST'
                          and delivered.target_id=intent.post_id)))
            order by intent.published_at desc, intent.post_id desc
            limit :limit
            """.formatted(
            EventProfilePublicationRepository.SQL_START_SECONDS,
            EventProfilePublicationRepository.SQL_END_SECONDS,
            EventProfilePublicationRepository.SQL_START_SECONDS);

    private final NamedParameterJdbcTemplate jdbc;
    private final EventShareUrlBuilder shareUrls;

    public MusicianFeedListenerEventShareCandidateProvider(
            NamedParameterJdbcTemplate jdbc,
            EventShareUrlBuilder shareUrls
    ) {
        this.jdbc = jdbc;
        this.shareUrls = shareUrls;
    }

    @Override public String providerId() { return "listener-event-profile-shares"; }
    @Override public Set<MusicianFeedItemType> supportedTypes() {
        return Set.of(MusicianFeedItemType.EVENT_PROFILE_SHARE);
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, timeout = 5)
    public List<MusicianFeedCandidate> findCandidates(MusicianFeedCandidateRequest request) {
        if (!request.supportedTypes().contains(MusicianFeedItemType.EVENT_PROFILE_SHARE)) return List.of();
        ZonedDateTime now = request.readAt().atZone(EVENT_ZONE);
        var parameters = new MapSqlParameterSource()
                .addValue("viewerId", request.viewerUserId())
                .addValue("feedSessionId", request.feedSessionId())
                .addValue("anchor", MusicianFeedJdbcSupport.timestamp(request.anchor()))
                .addValue("today", now.toLocalDate())
                .addValue("nowSeconds", now.toLocalTime().toSecondOfDay())
                .addValue("storageMidnight",
                        MusicianFeedJdbcSupport.hibernateUtcStorageTime(LocalTime.MIDNIGHT))
                .addValue("limit", request.limit());
        return jdbc.query(SQL, parameters, (row, index) -> {
            UUID postId = MusicianFeedJdbcSupport.uuid(row, "post_id");
            UUID eventId = MusicianFeedJdbcSupport.uuid(row, "event_id");
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
            var author = MusicianFeedJdbcSupport.author(row);
            return new MusicianFeedCandidate("EVENT_PROFILE_SHARE:" + postId,
                    MusicianFeedItemType.EVENT_PROFILE_SHARE, 1,
                    MusicianFeedJdbcSupport.instant(row, "published_at"),
                    MusicianFeedJdbcSupport.publicationReason(author, MusicianFeedReasonCode.DISCOVERY),
                    author, new MusicianFeedItemResponse.Target("EVENT_POST", postId),
                    MusicianFeedJdbcSupport.engagement(row, "EVENT_POST", postId), null,
                    MusicianFeedJdbcSupport.standardFeedback(),
                    new MusicianFeedPayloads.Event(event, row.getString("note"), postId),
                    1_010_000L, 0, MusicianFeedLane.FOLLOWING, false);
        });
    }

    private static String firstText(String... values) {
        return Arrays.stream(values).filter(value -> value != null && !value.isBlank()).findFirst().orElse(null);
    }
}
