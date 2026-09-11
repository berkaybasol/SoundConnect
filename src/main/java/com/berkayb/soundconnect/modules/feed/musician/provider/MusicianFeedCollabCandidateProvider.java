package com.berkayb.soundconnect.modules.feed.musician.provider;

import com.berkayb.soundconnect.modules.collab.dto.response.*;
import com.berkayb.soundconnect.modules.collab.enums.*;
import com.berkayb.soundconnect.modules.feed.musician.api.*;
import com.berkayb.soundconnect.modules.feed.musician.candidate.*;
import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

@Component
public class MusicianFeedCollabCandidateProvider implements MusicianFeedCandidateProvider {
    private static final UUID EMPTY_UUID = new UUID(0, 0);
    private static final String SQL = """
            select listing.id, listing.version, listing.status, listing.closure_reason, listing.cadence,
                   listing.wanted_type, listing.instrument_id, instrument.name as instrument_name,
                   listing.branch, listing.custom_specialty, listing.title, listing.description,
                   listing.city_id, city.name as city_name, listing.scheduled_at, listing.expires_at,
                   listing.fee_amount_minor, listing.currency, listing.published_at, listing.closed_at,
                   listing.created_at, listing.owner_user_id,
                   actor.id as actor_id, actor.profile_type, actor.source_profile_id,
                   actor.display_name, actor.avatar_url, actor.rating_sum, actor.review_count,
                   actor.completed_job_count, account.user_name as contact_username,
                   (case when actor.profile_type='BAND' then band_follow.id is not null
                         else following.id is not null end) as followed_by_viewer,
                   (listing.owner_user_id=:viewerId) as owned_by_viewer,
                   (select count(*) from tbl_collab_application application
                       where application.collab_id=listing.id) as application_count,
                   exists(select 1 from tbl_collab_application mine
                       where mine.collab_id=listing.id and mine.applicant_user_id=:viewerId) as applied_by_me,
                   exists(select 1 from tbl_collab_saved_listing saved
                       where saved.collab_id=listing.id and saved.user_id=:viewerId) as saved_by_me,
                   (listing.city_id=:cityId and :hasCity) as city_match,
                   (listing.instrument_id in (:instrumentIds) and :hasInstruments) as instrument_match
            from tbl_collab listing
            join tbl_collab_actor actor on actor.id=listing.publisher_actor_id and actor.active
            join tbl_user account on account.id=listing.owner_user_id
            join tbl_city city on city.id=listing.city_id
            left join tbl_instrument instrument on instrument.id=listing.instrument_id
            left join tbl_follow following on actor.profile_type<>'BAND'
                and following.follower_id=:viewerId
                and following.following_id=listing.owner_user_id
            left join tbl_band_follow band_follow on actor.profile_type='BAND'
                and band_follow.follower_id=:viewerId and band_follow.band_id=actor.source_profile_id
            where listing.status='OPEN' and listing.published_at is not null
              and listing.published_at<=:anchor
              and (listing.expires_at is null or listing.expires_at>:readAt)
              and actor.profile_type in ('MUSICIAN','BAND','VENUE','STUDIO')
              and account.status='ACTIVE' and account.email_verified and account.erased_at is null
              and listing.owner_user_id<>:viewerId
              and not (actor.profile_type='BAND' and exists (
                    select 1 from tbl_band_member own_member
                    where own_member.band_id=actor.source_profile_id
                      and own_member.user_id=:viewerId and own_member.status='ACTIVE'))
              and not exists(select 1 from tbl_musician_feed_feedback feedback
                  where feedback.viewer_user_id=:viewerId and (
                    (feedback.action in ('HIDE','REPORT')
                     and feedback.item_id='COLLAB:' || listing.id::text)
                    or (feedback.action='MUTE_AUTHOR'
                        and feedback.author_profile_type=actor.profile_type
                        and feedback.author_profile_id=actor.source_profile_id)))
              and (
                (actor.profile_type='MUSICIAN' and exists (
                    select 1 from tbl_musician_profile source
                    where source.id=actor.source_profile_id and source.user_id=listing.owner_user_id))
                or (actor.profile_type='BAND' and exists (
                    select 1 from tbl_band_member founder
                    where founder.band_id=actor.source_profile_id
                      and founder.user_id=listing.owner_user_id
                      and founder.status='ACTIVE' and founder.band_role='FOUNDER'))
                or (actor.profile_type='VENUE' and exists (
                    select 1 from tbl_venues source
                    where source.id=actor.source_profile_id and source.owner_id=listing.owner_user_id
                      and source.status='APPROVED'))
                or (actor.profile_type='STUDIO' and exists (
                    select 1 from tbl_studio_profile source
                    where source.id=actor.source_profile_id and source.user_id=listing.owner_user_id))
              )
              and not exists(select 1 from tbl_musician_feed_delivery delivered
                  where delivered.viewer_user_id=:viewerId and delivered.feed_session_id=:feedSessionId
                    and (delivered.item_id='COLLAB:' || listing.id::text
                      or (delivered.item_type<>'ACTIVITY_COMMENT' and delivered.target_type='COLLAB'
                          and delivered.target_id=listing.id)))
              and (
                (:pool='FOLLOWING' and (case when actor.profile_type='BAND'
                    then band_follow.id is not null else following.id is not null end))
                or (:pool='RELEVANT' and not (case when actor.profile_type='BAND'
                    then band_follow.id is not null else following.id is not null end)
                    and ((:hasCity and listing.city_id=:cityId)
                         or (:hasInstruments and listing.instrument_id in (:instrumentIds))))
                or (:pool='GENERAL' and not (case when actor.profile_type='BAND'
                    then band_follow.id is not null else following.id is not null end)
                    and not ((:hasCity and listing.city_id=:cityId)
                             or (:hasInstruments and listing.instrument_id in (:instrumentIds))))
              )
            order by
              case
                when listing.city_id=:cityId and :hasCity
                     and listing.instrument_id in (:instrumentIds) and :hasInstruments then 0
                when listing.city_id=:cityId and :hasCity then 1
                when listing.instrument_id in (:instrumentIds) and :hasInstruments then 2
                else 3 end,
              listing.published_at desc, listing.id desc
            limit :limit
            """;
    private static final String GENRES_SQL = """
            select collab_id, genre from tbl_collab_genre
            where collab_id in (:listingIds) order by collab_id, position
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public MusicianFeedCollabCandidateProvider(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override public String providerId() { return "collab-opportunities"; }
    @Override public Set<MusicianFeedItemType> supportedTypes() { return Set.of(MusicianFeedItemType.COLLAB); }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, timeout = 5)
    public List<MusicianFeedCandidate> findCandidates(MusicianFeedCandidateRequest request) {
        if (!request.supportedTypes().contains(MusicianFeedItemType.COLLAB)) return List.of();
        UUID cityId = request.personalization().opportunityCityId();
        Set<UUID> instruments = request.personalization().instrumentIds();
        var parameters = new MapSqlParameterSource()
                .addValue("viewerId", request.viewerUserId())
                .addValue("feedSessionId", request.feedSessionId())
                .addValue("anchor", MusicianFeedJdbcSupport.timestamp(request.anchor()))
                .addValue("readAt", MusicianFeedJdbcSupport.timestamp(request.readAt()))
                .addValue("cityId", cityId == null ? EMPTY_UUID : cityId)
                .addValue("hasCity", cityId != null)
                .addValue("instrumentIds", instruments.isEmpty() ? List.of(EMPTY_UUID) : instruments)
                .addValue("hasInstruments", !instruments.isEmpty());
        int followingLimit = request.limit() / 2;
        int relevantLimit = Math.max(1, request.limit() * 4 / 10);
        int generalLimit = Math.max(0, request.limit() - followingLimit - relevantLimit);
        List<ListingRow> rows = new ArrayList<>(request.limit());
        if (followingLimit > 0) rows.addAll(jdbc.query(SQL, parameters.addValue("pool", "FOLLOWING")
                .addValue("limit", followingLimit), this::row));
        if (relevantLimit > 0) rows.addAll(jdbc.query(SQL, parameters.addValue("pool", "RELEVANT")
                .addValue("limit", relevantLimit), this::row));
        if (generalLimit > 0) rows.addAll(jdbc.query(SQL, parameters.addValue("pool", "GENERAL")
                .addValue("limit", generalLimit), this::row));
        if (rows.isEmpty()) return List.of();
        Map<UUID, List<String>> genres = new HashMap<>();
        jdbc.query(GENRES_SQL, Map.of("listingIds", rows.stream().map(ListingRow::id).toList()), result -> {
            UUID listingId = MusicianFeedJdbcSupport.uuid(result, "collab_id");
            genres.computeIfAbsent(listingId, ignored -> new ArrayList<>()).add(result.getString("genre"));
        });
        return rows.stream().map(value -> candidate(value, genres.getOrDefault(value.id(), List.of()))).toList();
    }

    private ListingRow row(ResultSet row, int index) throws SQLException {
        long reviewCount = row.getLong("review_count");
        BigDecimal rating = reviewCount == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(row.getLong("rating_sum"))
                .divide(BigDecimal.valueOf(reviewCount), 2, RoundingMode.HALF_UP);
        ProfileType profileType = ProfileType.valueOf(row.getString("profile_type"));
        var actor = new CollabActorSummary(MusicianFeedJdbcSupport.uuid(row, "actor_id"), profileType,
                MusicianFeedJdbcSupport.uuid(row, "source_profile_id"),
                MusicianFeedJdbcSupport.uuid(row, "owner_user_id"), row.getString("contact_username"),
                row.getString("display_name"), row.getString("avatar_url"), rating, reviewCount,
                row.getLong("completed_job_count"));
        var author = new MusicianFeedItemResponse.Author(actor.contactUserId(), actor.sourceProfileId(),
                profileType.name(), actor.contactUsername(), actor.displayName(), actor.avatarUrl(),
                row.getBoolean("followed_by_viewer"));
        return new ListingRow(MusicianFeedJdbcSupport.uuid(row, "id"), row.getLong("version"),
                CollabListingStatus.valueOf(row.getString("status")),
                enumOrNull(CollabClosureReason.class, row.getString("closure_reason")),
                CollabCadence.valueOf(row.getString("cadence")),
                CollabWantedType.valueOf(row.getString("wanted_type")),
                MusicianFeedJdbcSupport.uuid(row, "instrument_id"), row.getString("instrument_name"),
                enumOrNull(CollabBranch.class, row.getString("branch")), row.getString("custom_specialty"),
                row.getString("title"), row.getString("description"),
                MusicianFeedJdbcSupport.uuid(row, "city_id"), row.getString("city_name"),
                MusicianFeedJdbcSupport.instant(row, "scheduled_at"),
                MusicianFeedJdbcSupport.instant(row, "expires_at"),
                (Long) row.getObject("fee_amount_minor"), row.getString("currency"),
                MusicianFeedJdbcSupport.instant(row, "published_at"),
                MusicianFeedJdbcSupport.instant(row, "closed_at"),
                MusicianFeedJdbcSupport.instant(row, "created_at"), actor, author,
                row.getLong("application_count"), row.getBoolean("owned_by_viewer"),
                row.getBoolean("applied_by_me"), row.getBoolean("saved_by_me"),
                row.getBoolean("city_match"), row.getBoolean("instrument_match"));
    }

    private MusicianFeedCandidate candidate(ListingRow value, List<String> genres) {
        CollabFeeStatus feeStatus = value.feeAmountMinor() != null ? CollabFeeStatus.SPECIFIED
                : value.cadence() == CollabCadence.EXTRA || value.actor().profileType() == ProfileType.VENUE
                ? CollabFeeStatus.UNSPECIFIED : CollabFeeStatus.NOT_APPLICABLE;
        CollabListingResponse listing = new CollabListingResponse(value.id(), value.version(), value.status(),
                value.closureReason(), value.cadence(), value.wantedType(),
                value.instrumentId() == null ? null : new CollabInstrumentSummary(value.instrumentId(), value.instrumentName()),
                value.branch(), value.customSpecialty(), value.title(), value.description(),
                new CollabCitySummary(value.cityId(), value.cityName()), List.copyOf(genres), value.scheduledAt(),
                value.expiresAt(), value.feeAmountMinor(), value.currency(), feeStatus, value.publishedAt(),
                value.closedAt(), value.createdAt(), value.actor(), value.applicationCount(), value.ownedByViewer(),
                value.appliedByMe(), value.savedByMe());
        int relevance = value.cityMatch() && value.instrumentMatch() ? 260_000
                : value.cityMatch() ? 170_000 : value.instrumentMatch() ? 120_000 : 0;
        MusicianFeedReasonCode relevanceReason = value.cityMatch() && value.instrumentMatch()
                ? MusicianFeedReasonCode.CITY_AND_INSTRUMENT_MATCH
                : value.cityMatch() ? MusicianFeedReasonCode.CITY_MATCH
                : value.instrumentMatch() ? MusicianFeedReasonCode.INSTRUMENT_MATCH
                : MusicianFeedReasonCode.DISCOVERY;
        var reason = value.author().followedByViewer()
                ? MusicianFeedJdbcSupport.publicationReason(value.author(), relevanceReason)
                : new MusicianFeedItemResponse.Reason(relevanceReason, List.of(), 0);
        return new MusicianFeedCandidate("COLLAB:" + value.id(), MusicianFeedItemType.COLLAB, 1,
                value.publishedAt(), reason, value.author(),
                new MusicianFeedItemResponse.Target("COLLAB", value.id()), null, null,
                MusicianFeedJdbcSupport.standardFeedback(), new MusicianFeedPayloads.Collab(listing),
                value.author().followedByViewer() ? 960_000L : 340_000L,
                relevance, value.author().followedByViewer() ? MusicianFeedLane.FOLLOWING
                        : relevance > 0 ? MusicianFeedLane.RELEVANT_OPPORTUNITY
                        : MusicianFeedLane.GENERAL_DISCOVERY, value.ownedByViewer());
    }

    private static <T extends Enum<T>> T enumOrNull(Class<T> type, String value) {
        return value == null ? null : Enum.valueOf(type, value);
    }

    private record ListingRow(
            UUID id, long version, CollabListingStatus status, CollabClosureReason closureReason,
            CollabCadence cadence, CollabWantedType wantedType, UUID instrumentId, String instrumentName,
            CollabBranch branch, String customSpecialty, String title, String description,
            UUID cityId, String cityName, Instant scheduledAt, Instant expiresAt,
            Long feeAmountMinor, String currency, Instant publishedAt, Instant closedAt, Instant createdAt,
            CollabActorSummary actor, MusicianFeedItemResponse.Author author, long applicationCount,
            boolean ownedByViewer, boolean appliedByMe, boolean savedByMe,
            boolean cityMatch, boolean instrumentMatch
    ) { }
}
