package com.berkayb.soundconnect.modules.feed.musician.provider;

import com.berkayb.soundconnect.modules.feed.musician.api.*;
import com.berkayb.soundconnect.modules.feed.musician.candidate.*;
import com.berkayb.soundconnect.modules.feed.musician.core.BackstageFeedAudience;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.*;

/** Audience-specific candidate policy; visibility, payloads and delivery remain shared. */
final class MusicianFeedArtistDiscovery {
    private MusicianFeedArtistDiscovery() { }

    static boolean forVenue(MusicianFeedCandidateRequest request) {
        return request.audience() == BackstageFeedAudience.VENUE;
    }

    static boolean forListener(MusicianFeedCandidateRequest request) {
        return request.audience() == BackstageFeedAudience.LISTENER;
    }

    /** Matching only: neither a private opportunity city nor member cities enter the payload. */
    static String cityMatchSql(String profileType, String profileId, String accountCity) {
        return """
                coalesce((:venueAudience and :hasCity and (
                    (%1$s='MUSICIAN' and coalesce((select preference.opportunity_city_id
                        from tbl_musician_feed_preferences preference
                        where preference.musician_profile_id=%2$s), %3$s)=:cityId)
                    or (%1$s='BAND' and exists(
                        select 1 from tbl_band_member city_member
                        join tbl_user city_account on city_account.id=city_member.user_id
                            and city_account.status='ACTIVE' and city_account.email_verified
                            and city_account.erased_at is null
                        left join tbl_musician_profile city_profile on city_profile.user_id=city_account.id
                        left join tbl_musician_feed_preferences city_preference
                            on city_preference.musician_profile_id=city_profile.id
                        where city_member.band_id=%2$s and city_member.status='ACTIVE'
                            and coalesce(city_preference.opportunity_city_id,city_account.city_id)=:cityId)))),false)
                """.formatted(profileType, profileId, accountCity).strip();
    }

    static <T> List<T> findPublications(NamedParameterJdbcTemplate jdbc, String sql,
                                      MusicianFeedCandidateRequest request, RowMapper<T> mapper) {
        boolean venue = forVenue(request);
        boolean listener = forListener(request);
        UUID city = request.personalization().opportunityCityId();
        int discoveryLimit = Math.max(1, request.limit() / (venue ? 2 : 4));
        int followingLimit = Math.max(0, request.limit() - discoveryLimit);
        var parameters = new MapSqlParameterSource()
                .addValue("viewerId", request.viewerUserId())
                .addValue("feedSessionId", request.feedSessionId())
                .addValue("anchor", MusicianFeedJdbcSupport.timestamp(request.anchor()))
                .addValue("venueAudience", venue)
                .addValue("listenerAudience", listener)
                .addValue("hasCity", city != null)
                .addValue("cityId", city == null ? new UUID(0, 0) : city)
                .addValue("artistCityPool", "ALL");
        List<T> result = new ArrayList<>(request.limit());
        if (followingLimit > 0) result.addAll(jdbc.query(sql, parameters
                .addValue("followingPool", true).addValue("limit", followingLimit), mapper));
        if (venue || listener) discoveryLimit = request.limit() - result.size();
        int beforeDiscovery = result.size();
        if (venue && city != null && discoveryLimit > 0) result.addAll(jdbc.query(sql, parameters
                .addValue("followingPool", false).addValue("artistCityPool", "LOCAL")
                .addValue("limit", discoveryLimit), mapper));
        int remaining = discoveryLimit - (result.size() - beforeDiscovery);
        if (remaining > 0) result.addAll(jdbc.query(sql, parameters
                .addValue("followingPool", false).addValue("artistCityPool", venue ? "OTHER" : "ALL")
                .addValue("limit", remaining), mapper));
        return List.copyOf(result);
    }

    static MusicianFeedCandidate prioritize(MusicianFeedCandidate value, MusicianFeedCandidateRequest request,
                                           boolean cityMatch, boolean performanceOrProfile) {
        if (forListener(request) && performanceOrProfile && value.author() != null
                && Set.of("MUSICIAN", "BAND", "VENUE").contains(value.author().profileType())
                && Set.of(MusicianFeedItemType.TRACK, MusicianFeedItemType.PROFILE_MEDIA).contains(value.type())) {
            boolean followed = value.lane() == MusicianFeedLane.FOLLOWING;
            return new MusicianFeedCandidate(value.itemId(), value.type(), value.payloadVersion(), value.occurredAt(),
                    value.reason(), value.author(), value.target(), value.engagement(), value.promotion(),
                    value.feedbackCapabilities(), value.payload(), value.baseScore(), value.relevanceScore(),
                    followed ? MusicianFeedLane.FOLLOWING : MusicianFeedLane.RELEVANT_OPPORTUNITY,
                    value.ownedByViewer(), value.announcementPlacement());
        }
        if (!forVenue(request) || value.author() == null
                || !Set.of("MUSICIAN", "BAND").contains(value.author().profileType())) return value;
        int relevance = performanceOrProfile ? (cityMatch ? 170_000 : 80_000) : (cityMatch ? 60_000 : 0);
        boolean followed = value.lane() == MusicianFeedLane.FOLLOWING;
        var reason = followed ? value.reason() : new MusicianFeedItemResponse.Reason(
                cityMatch ? MusicianFeedReasonCode.CITY_MATCH : MusicianFeedReasonCode.DISCOVERY, List.of(), 0);
        return new MusicianFeedCandidate(value.itemId(), value.type(), value.payloadVersion(), value.occurredAt(),
                reason, value.author(), value.target(), value.engagement(), value.promotion(),
                value.feedbackCapabilities(), value.payload(), value.baseScore(), relevance,
                followed ? MusicianFeedLane.FOLLOWING : performanceOrProfile ? MusicianFeedLane.RELEVANT_OPPORTUNITY
                        : MusicianFeedLane.GENERAL_DISCOVERY, value.ownedByViewer(), value.announcementPlacement());
    }
}
