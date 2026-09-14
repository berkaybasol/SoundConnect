package com.berkayb.soundconnect.modules.feed.musician.provider;

import com.berkayb.soundconnect.modules.feed.musician.api.*;
import com.berkayb.soundconnect.modules.feed.musician.candidate.*;
import com.berkayb.soundconnect.modules.feed.musician.core.BackstageFeedAudience;
import com.berkayb.soundconnect.modules.feed.musician.feedback.MusicianFeedFeedbackSnapshot;
import com.berkayb.soundconnect.modules.feed.musician.personalization.MusicianFeedPersonalizationSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MusicianFeedArtistDiscoveryTest {
    @Test void venuePrefersLocalArtistsWithoutChangingPayloadOrFollowingIdentity() {
        var artist = candidate("MUSICIAN", MusicianFeedLane.GENERAL_DISCOVERY);
        var request = request(BackstageFeedAudience.VENUE, UUID.randomUUID());
        var local = MusicianFeedArtistDiscovery.prioritize(artist, request, true, true);
        var fallback = MusicianFeedArtistDiscovery.prioritize(artist, request, false, true);
        assertThat(local.lane()).isEqualTo(MusicianFeedLane.RELEVANT_OPPORTUNITY);
        assertThat(local.reason().code()).isEqualTo(MusicianFeedReasonCode.CITY_MATCH);
        assertThat(local.relevanceScore()).isGreaterThan(fallback.relevanceScore());
        assertThat(fallback.lane()).isEqualTo(MusicianFeedLane.RELEVANT_OPPORTUNITY);
        assertThat(fallback.reason().code()).isEqualTo(MusicianFeedReasonCode.DISCOVERY);
        assertThat(local.payload()).isSameAs(artist.payload());
        assertThat(local.author()).isSameAs(artist.author());
        var followed = candidate("BAND", MusicianFeedLane.FOLLOWING);
        assertThat(MusicianFeedArtistDiscovery.prioritize(followed, request, true, true).lane())
                .isEqualTo(MusicianFeedLane.FOLLOWING);
    }

    @Test void photosDoNotBecomeReservedPerformancesAndMusicianPolicyIsUnchanged() {
        var artist = candidate("MUSICIAN", MusicianFeedLane.GENERAL_DISCOVERY);
        assertThat(MusicianFeedArtistDiscovery.prioritize(artist, request(BackstageFeedAudience.MUSICIAN, null), true, true))
                .isSameAs(artist);
        var photo = MusicianFeedArtistDiscovery.prioritize(artist, request(BackstageFeedAudience.VENUE, null), true, false);
        assertThat(photo.lane()).isEqualTo(MusicianFeedLane.GENERAL_DISCOVERY);
        var venue = candidate("VENUE", MusicianFeedLane.FOLLOWING);
        assertThat(MusicianFeedArtistDiscovery.prioritize(venue, request(BackstageFeedAudience.VENUE, null), true, true))
                .isSameAs(venue);
    }

    @Test void sparseVenuePublicationsBackfillAllUnusedCapacityWithNationalArtists() {
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        List<Map<String, Object>> reads = new ArrayList<>();
        when(jdbc.query(anyString(), any(SqlParameterSource.class), org.mockito.ArgumentMatchers.<RowMapper<String>>any()))
                .thenAnswer(call -> {
                    SqlParameterSource parameters = call.getArgument(1);
                    reads.add(Map.of("following", parameters.getValue("followingPool"),
                            "pool", parameters.getValue("artistCityPool"), "limit", parameters.getValue("limit")));
                    if (parameters.getValue("artistCityPool").equals("OTHER")) {
                        return java.util.stream.IntStream.range(0, (Integer) parameters.getValue("limit"))
                                .mapToObj(index -> "artist-" + index).toList();
                    }
                    return List.of();
                });
        var result = MusicianFeedArtistDiscovery.findPublications(jdbc, "bounded", request(BackstageFeedAudience.VENUE, UUID.randomUUID()),
                (row, index) -> "unused");
        assertThat(result).hasSize(20);
        assertThat(reads).containsExactly(
                Map.of("following", true, "pool", "ALL", "limit", 10),
                Map.of("following", false, "pool", "LOCAL", "limit", 20),
                Map.of("following", false, "pool", "OTHER", "limit", 20));
    }

    @Test void musicianKeepsItsOriginalTwoPoolBudgetAndNoVenueLocalQuery() {
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        List<Integer> limits = new ArrayList<>();
        when(jdbc.query(anyString(), any(SqlParameterSource.class), org.mockito.ArgumentMatchers.<RowMapper<String>>any()))
                .thenAnswer(call -> {
                    SqlParameterSource parameters = call.getArgument(1);
                    assertThat(parameters.getValue("venueAudience")).isEqualTo(false);
                    assertThat(parameters.getValue("artistCityPool")).isEqualTo("ALL");
                    limits.add((Integer) parameters.getValue("limit"));
                    return List.of();
                });
        MusicianFeedArtistDiscovery.findPublications(jdbc, "bounded", request(BackstageFeedAudience.MUSICIAN, UUID.randomUUID()),
                (row, index) -> "unused");
        assertThat(limits).containsExactly(15, 5);
    }

    @Test void listenerDiscoveryCanUseUnfilledFollowingCapacityWithoutRestrictingMusicToACity() {
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        List<Integer> limits = new ArrayList<>();
        when(jdbc.query(anyString(), any(SqlParameterSource.class), org.mockito.ArgumentMatchers.<RowMapper<String>>any()))
                .thenAnswer(call -> {
                    SqlParameterSource parameters = call.getArgument(1);
                    assertThat(parameters.getValue("venueAudience")).isEqualTo(false);
                    assertThat(parameters.getValue("listenerAudience")).isEqualTo(true);
                    assertThat(parameters.getValue("artistCityPool")).isEqualTo("ALL");
                    limits.add((Integer) parameters.getValue("limit"));
                    return List.of();
                });
        var request = request(BackstageFeedAudience.LISTENER, UUID.randomUUID());
        MusicianFeedArtistDiscovery.findPublications(jdbc, "bounded", request, (row, index) -> "unused");
        assertThat(limits).containsExactly(15, 20);
        var artist = candidate("MUSICIAN", MusicianFeedLane.GENERAL_DISCOVERY);
        assertThat(MusicianFeedArtistDiscovery.prioritize(artist, request, false, true).lane())
                .isEqualTo(MusicianFeedLane.RELEVANT_OPPORTUNITY);
        var followedListener = candidate("LISTENER", MusicianFeedLane.FOLLOWING);
        assertThat(MusicianFeedArtistDiscovery.prioritize(followedListener, request, false, true)).isSameAs(followedListener);
    }

    private static MusicianFeedCandidateRequest request(BackstageFeedAudience audience, UUID city) {
        Instant now = Instant.parse("2026-09-14T12:00:00Z");
        return new MusicianFeedCandidateRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), now, now,
                20, EnumSet.allOf(MusicianFeedItemType.class), new MusicianFeedPersonalizationSnapshot(city, Set.of(), null),
                MusicianFeedFeedbackSnapshot.empty()).withAudience(audience);
    }

    private static MusicianFeedCandidate candidate(String profileType, MusicianFeedLane lane) {
        UUID id = UUID.randomUUID();
        var author = new MusicianFeedItemResponse.Author(UUID.randomUUID(), id, profileType, "artist", "Artist", null,
                lane == MusicianFeedLane.FOLLOWING);
        return new MusicianFeedCandidate("TRACK:" + id, MusicianFeedItemType.TRACK, 1, Instant.EPOCH,
                new MusicianFeedItemResponse.Reason(MusicianFeedReasonCode.DISCOVERY, List.of(), 0),
                author, new MusicianFeedItemResponse.Target("MEDIA", id), null, null, List.of(), Map.of("public", "payload"),
                300_000, 0, lane, false);
    }
}
