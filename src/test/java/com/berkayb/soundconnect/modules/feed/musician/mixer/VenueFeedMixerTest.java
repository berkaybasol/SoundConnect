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

import static org.assertj.core.api.Assertions.assertThat;

class VenueFeedMixerTest {
    private static final Instant NOW = Instant.parse("2026-09-14T12:00:00Z");
    private static final UUID VIEWER = new UUID(1, 1);
    private final MusicianFeedMixer mixer = new MusicianFeedMixer();

    @Test void venueReservesArtistsWhileMusicianKeepsItsMatchedCollabReservation() {
        List<MusicianFeedCandidate> pool = denseFollowing();
        IntStream.range(0, 8).forEach(index -> pool.add(candidate("artist-" + index,
                MusicianFeedItemType.PROFILE, index % 2 == 0 ? "MUSICIAN" : "BAND",
                300_000, MusicianFeedLane.RELEVANT_OPPORTUNITY, Map.of())));
        IntStream.range(0, 8).forEach(index -> pool.add(candidate("job-" + index,
                MusicianFeedItemType.COLLAB, "VENUE", 600_000,
                MusicianFeedLane.RELEVANT_OPPORTUNITY, Map.of())));

        var venue = mix(pool, BackstageFeedAudience.VENUE);
        var musician = mix(pool, BackstageFeedAudience.MUSICIAN);

        assertThat(venue.items()).hasSize(20);
        assertThat(venue.items().stream().filter(item -> item.id().startsWith("artist-")).count()).isGreaterThanOrEqualTo(4);
        assertThat(venue.items().subList(0, 5)).anySatisfy(item -> assertThat(item.id()).startsWith("artist-"));
        assertThat(musician.items().stream().filter(item -> item.id().startsWith("job-")).count()).isGreaterThanOrEqualTo(4);
        assertThat(musician.items()).noneSatisfy(item -> assertThat(item.id()).startsWith("artist-"));
        assertThat(mix(pool, BackstageFeedAudience.VENUE)).isEqualTo(venue);
    }

    @Test void performanceVideosReceiveArtistReservationButPhotosDoNotConsumeIt() {
        List<MusicianFeedCandidate> pool = denseFollowing();
        IntStream.range(0, 8).forEach(index -> {
            pool.add(candidate("photo-" + index, MusicianFeedItemType.PROFILE_MEDIA, "MUSICIAN", 450_000,
                    MusicianFeedLane.RELEVANT_OPPORTUNITY, media("IMAGE")));
            pool.add(candidate("video-" + index, MusicianFeedItemType.PROFILE_MEDIA, "BAND", 300_000,
                    MusicianFeedLane.RELEVANT_OPPORTUNITY, media("VIDEO")));
        });
        var page = mix(pool, BackstageFeedAudience.VENUE);
        assertThat(page.items().stream().filter(item -> item.id().startsWith("video-")).count()).isGreaterThanOrEqualTo(4);
        assertThat(page.items()).noneSatisfy(item -> assertThat(item.id()).startsWith("photo-"));
    }

    @Test void venueNeverRendersMusicianCompletionEvenIfAdvertisedOrProvided() {
        var completion = candidate("completion", MusicianFeedItemType.PROFILE_COMPLETION, "MUSICIAN",
                5_000_000, MusicianFeedLane.SYSTEM, Map.of());
        var track = candidate("artist", MusicianFeedItemType.TRACK, "MUSICIAN", 300_000,
                MusicianFeedLane.RELEVANT_OPPORTUNITY, Map.of());
        assertThat(mix(List.of(completion, track), BackstageFeedAudience.VENUE).items())
                .extracting(MusicianFeedItemResponse::id).containsExactly("artist");
        assertThat(mix(List.of(completion, track), BackstageFeedAudience.MUSICIAN).items())
                .extracting(MusicianFeedItemResponse::id).contains("completion", "artist");
    }

    @Test void sparseVenueFeedReturnsAllAvailableArtistsWithoutFillingWithPlaceSuggestions() {
        List<MusicianFeedCandidate> artists = IntStream.range(0, 3).mapToObj(index -> candidate("artist-" + index,
                MusicianFeedItemType.PROFILE, "MUSICIAN", 300_000,
                MusicianFeedLane.RELEVANT_OPPORTUNITY, Map.of())).toList();
        var page = mix(artists, BackstageFeedAudience.VENUE);
        assertThat(page.items()).hasSize(3);
        assertThat(page.hasMore()).isFalse();
    }

    @Test void legacyMusicianOverloadsRetainTheSameOrdering() {
        var pool = denseFollowing();
        var legacy = mixer.mix(VIEWER, NOW, 20, EnumSet.allOf(MusicianFeedItemType.class),
                MusicianFeedFeedbackSnapshot.empty(), pool, List.of(), null, 0);
        assertThat(mix(pool, BackstageFeedAudience.MUSICIAN)).isEqualTo(legacy);
        assertThat(mix(pool, BackstageFeedAudience.VENUE)).isEqualTo(legacy);
    }

    private MusicianFeedMixer.MixedPage mix(List<MusicianFeedCandidate> pool, BackstageFeedAudience audience) {
        return mixer.mix(VIEWER, NOW, 20, EnumSet.allOf(MusicianFeedItemType.class),
                MusicianFeedFeedbackSnapshot.empty(), pool, List.of(), null, 0, 0, false, null, null,
                0, 0, 0, MusicianFeedAnnouncementPlan.EMPTY, null, audience);
    }

    private static List<MusicianFeedCandidate> denseFollowing() {
        return new ArrayList<>(IntStream.range(0, 30).mapToObj(index -> candidate("following-" + index,
                MusicianFeedItemType.TRACK, "VENUE", 1_000_000, MusicianFeedLane.FOLLOWING, Map.of())).toList());
    }

    private static MusicianFeedCandidate candidate(String id, MusicianFeedItemType type, String authorType,
                                                   long score, MusicianFeedLane lane, Object payload) {
        UUID authorId = UUID.nameUUIDFromBytes(("author-" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        UUID targetId = UUID.nameUUIDFromBytes(id.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var author = new MusicianFeedItemResponse.Author(authorId, authorId, authorType, "artist", "Artist", null,
                lane == MusicianFeedLane.FOLLOWING);
        return new MusicianFeedCandidate(id, type, 1, NOW.minusSeconds(60),
                new MusicianFeedItemResponse.Reason(MusicianFeedReasonCode.DISCOVERY, List.of(), 0),
                author, new MusicianFeedItemResponse.Target("PROFILE", targetId), null, null, List.of(), payload,
                score, 0, lane, false);
    }

    private static MusicianFeedPayloads.ProfileMedia media(String kind) {
        return new MusicianFeedPayloads.ProfileMedia(new UUID(0, 7), kind, "https://media.test/view",
                "https://media.test/play", null, "Performance", null, 120, 1080, 720);
    }
}
