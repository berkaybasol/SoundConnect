package com.berkayb.soundconnect.modules.feed.musician.mixer;

import com.berkayb.soundconnect.modules.feed.musician.api.*;
import com.berkayb.soundconnect.modules.feed.musician.announcement.MusicianFeedAnnouncementPlan;
import com.berkayb.soundconnect.modules.feed.musician.candidate.*;
import com.berkayb.soundconnect.modules.feed.musician.core.BackstageFeedAudience;
import com.berkayb.soundconnect.modules.feed.musician.feedback.MusicianFeedFeedbackSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;

class ListenerFeedMixerTest {
    private static final Instant NOW = Instant.parse("2026-09-14T12:00:00Z");
    private final MusicianFeedMixer mixer = new MusicianFeedMixer();

    @Test void denseFollowedSocialContentLeavesRoomForMusicAndEventsWithoutDiscardingFriends() {
        var pool = social();
        IntStream.range(0, 8).forEach(index -> pool.add(candidate("music-" + index,
                index % 2 == 0 ? MusicianFeedItemType.TRACK : MusicianFeedItemType.EVENT,
                "MUSICIAN", 300_000, MusicianFeedLane.RELEVANT_OPPORTUNITY, Map.of())));
        var result = mix(pool, List.of());
        assertThat(result.items()).hasSize(20);
        assertThat(result.items().stream().filter(item -> item.id().startsWith("music-")).count()).isGreaterThanOrEqualTo(4);
        assertThat(result.items().stream().filter(item -> item.id().startsWith("friend-")).count()).isGreaterThanOrEqualTo(12);
        assertThat(result.items().subList(0, 5)).anySatisfy(item -> assertThat(item.id()).startsWith("music-"));
        assertThat(mix(pool, List.of())).isEqualTo(result);
    }

    @Test void performanceVideoGetsTheMusicReservationWhileOrdinaryPhotosKeepNormalRanking() {
        var pool = social();
        IntStream.range(0, 8).forEach(index -> {
            pool.add(candidate("photo-" + index, MusicianFeedItemType.PROFILE_MEDIA, "MUSICIAN", 450_000,
                    MusicianFeedLane.RELEVANT_OPPORTUNITY, media("IMAGE")));
            pool.add(candidate("video-" + index, MusicianFeedItemType.PROFILE_MEDIA, "BAND", 300_000,
                    MusicianFeedLane.RELEVANT_OPPORTUNITY, media("VIDEO")));
        });
        var result = mix(pool, List.of());
        assertThat(result.items().stream().filter(item -> item.id().startsWith("video-")).count()).isGreaterThanOrEqualTo(4);
        assertThat(result.items()).noneSatisfy(item -> assertThat(item.id()).startsWith("photo-"));
    }

    @Test void coldStartUsesAvailableDiscoveryInsteadOfArtificiallyShortPages() {
        var pool = IntStream.range(0, 20).mapToObj(index -> candidate("music-" + index,
                MusicianFeedItemType.TRACK, "MUSICIAN", 300_000, MusicianFeedLane.GENERAL_DISCOVERY, Map.of())).toList();
        var result = mix(pool, List.of());
        assertThat(result.items()).hasSize(20);
        assertThat(result.hasMore()).isFalse();
    }

    @Test void completionAndOpaqueSponsoredCardsAreExcludedEvenWhenCallersAdvertiseThem() {
        var completion = candidate("completion", MusicianFeedItemType.PROFILE_COMPLETION, "MUSICIAN", 5_000_000,
                MusicianFeedLane.SYSTEM, Map.of());
        var track = candidate("music", MusicianFeedItemType.TRACK, "MUSICIAN", 300_000,
                MusicianFeedLane.RELEVANT_OPPORTUNITY, Map.of());
        var sponsor = candidate("sponsor", MusicianFeedItemType.SPONSORED, "STUDIO", 9_000_000,
                MusicianFeedLane.SYSTEM, Map.of());
        assertThat(mix(List.of(completion, track), List.of(sponsor)).items())
                .extracting(MusicianFeedItemResponse::id).containsExactly("music");
    }

    private MusicianFeedMixer.MixedPage mix(List<MusicianFeedCandidate> pool, List<MusicianFeedCandidate> sponsors) {
        return mixer.mix(new UUID(1, 1), NOW, 20, EnumSet.allOf(MusicianFeedItemType.class),
                MusicianFeedFeedbackSnapshot.empty(), pool, sponsors, null, 0, 0, false, null, null,
                0, 0, 0, MusicianFeedAnnouncementPlan.EMPTY, null, BackstageFeedAudience.LISTENER);
    }

    private static ArrayList<MusicianFeedCandidate> social() {
        return new ArrayList<>(IntStream.range(0, 30).mapToObj(index -> candidate("friend-" + index,
                MusicianFeedItemType.PROFILE_MEDIA, "LISTENER", 1_000_000, MusicianFeedLane.FOLLOWING, media("IMAGE"))).toList());
    }

    private static MusicianFeedCandidate candidate(String id, MusicianFeedItemType type, String authorType,
                                                   long score, MusicianFeedLane lane, Object payload) {
        UUID identity = UUID.nameUUIDFromBytes(id.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var author = new MusicianFeedItemResponse.Author(identity, identity, authorType, "user", "Name", null,
                lane == MusicianFeedLane.FOLLOWING);
        return new MusicianFeedCandidate(id, type, 1, NOW.minusSeconds(60),
                new MusicianFeedItemResponse.Reason(MusicianFeedReasonCode.DISCOVERY, List.of(), 0),
                author, new MusicianFeedItemResponse.Target("MEDIA", identity), null, null, List.of(), payload,
                score, 0, lane, false);
    }

    private static MusicianFeedPayloads.ProfileMedia media(String kind) {
        return new MusicianFeedPayloads.ProfileMedia(new UUID(0, 7), kind, "https://media.test/view",
                "https://media.test/play", null, "Performance", null, 120, 1080, 720);
    }
}
