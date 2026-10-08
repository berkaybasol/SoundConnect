package com.berkayb.soundconnect.modules.feed.musician.mixer;

import com.berkayb.soundconnect.modules.collab.dto.response.CollabListingResponse;
import com.berkayb.soundconnect.modules.collab.enums.*;
import com.berkayb.soundconnect.modules.feed.musician.api.*;
import com.berkayb.soundconnect.modules.feed.musician.announcement.MusicianFeedAnnouncementPlan;
import com.berkayb.soundconnect.modules.feed.musician.candidate.*;
import com.berkayb.soundconnect.modules.feed.musician.core.BackstageFeedAudience;
import com.berkayb.soundconnect.modules.feed.musician.feedback.MusicianFeedFeedbackSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.*;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class StudioFeedMixerTest {
    private static final Instant NOW = Instant.parse("2026-09-14T12:00:00Z");
    private final MusicianFeedMixer mixer = new MusicianFeedMixer();

    @Test void followedArtistsDoNotConsumeTheQuarterReservedForRequestsAndNewArtists() {
        var pool = following();
        for (int index = 0; index < 8; index++) {
            pool.add(artist("artist-" + index, 300_000));
            pool.add(request("request-" + index, CollabWantedType.STUDIO, 600_000));
        }
        var page = mix(pool, 20, MusicianFeedAnnouncementPlan.EMPTY, null);
        assertThat(page.items()).hasSize(20);
        assertThat(page.items().stream().filter(StudioFeedMixerTest::useful)).hasSize(5);
        assertThat(page.items()).anyMatch(value -> value.id().startsWith("request-"));
        assertThat(page.items()).anyMatch(value -> value.id().startsWith("artist-"));
        assertThat(page.items().subList(0, 5)).anyMatch(value -> value.id().startsWith("request-"));
        assertThat(page.items().stream().filter(value -> value.id().startsWith("following-"))).hasSize(15);
        assertThat(mix(pool, 20, MusicianFeedAnnouncementPlan.EMPTY, null)).isEqualTo(page);
    }

    @Test void evenDominantArtistScoresCannotCrowdOutAnExplicitStudioRequest() {
        var pool = following();
        for (int index = 0; index < 25; index++) pool.add(artist("artist-" + index, 3_000_000));
        pool.add(request("request-only", CollabWantedType.STUDIO, 10));
        assertThat(mix(pool, 20, MusicianFeedAnnouncementPlan.EMPTY, null).items())
                .anyMatch(value -> value.id().equals("request-only"));
    }

    @Test void evenDominantRequestScoresLeaveAnArtistDiscoveryWhenBothSourcesExist() {
        var pool = following();
        for (int index = 0; index < 25; index++) pool.add(request("request-" + index, CollabWantedType.STUDIO, 3_000_000));
        pool.add(artist("artist-only", 10));
        assertThat(mix(pool, 20, MusicianFeedAnnouncementPlan.EMPTY, null).items())
                .anyMatch(value -> value.id().equals("artist-only"));
    }

    @Test void musicianRequestsPhotosAndEventsDoNotConsumeStudioOpportunitySlots() {
        var pool = following();
        for (int index = 0; index < 8; index++) {
            pool.add(request("engineer-" + index, CollabWantedType.MUSICIAN, 600_000));
            pool.add(candidate("photo-" + index, MusicianFeedItemType.PROFILE_MEDIA, "MUSICIAN", 600_000,
                    MusicianFeedLane.RELEVANT_OPPORTUNITY, media("IMAGE")));
            pool.add(candidate("event-" + index, MusicianFeedItemType.EVENT, "MUSICIAN", 600_000,
                    MusicianFeedLane.RELEVANT_OPPORTUNITY, Map.of()));
            pool.add(candidate("artist-video-" + index, MusicianFeedItemType.PROFILE_MEDIA, "BAND", 300_000,
                    MusicianFeedLane.RELEVANT_OPPORTUNITY, media("VIDEO")));
        }
        var page = mix(pool, 20, MusicianFeedAnnouncementPlan.EMPTY, null);
        assertThat(page.items().stream().filter(value -> value.id().startsWith("artist-video-"))).hasSize(5);
        assertThat(page.items()).noneMatch(value -> value.id().startsWith("engineer-")
                || value.id().startsWith("photo-") || value.id().startsWith("event-"));
    }

    @ParameterizedTest @ValueSource(ints = {1, 2, 3})
    void smallPagesPreserveTheFollowingStream(int size) {
        var pool = following();
        pool.add(request("request-only", CollabWantedType.STUDIO, 10));
        pool.add(artist("artist-only", 10));
        assertThat(mix(pool, size, MusicianFeedAnnouncementPlan.EMPTY, null).items())
                .hasSize(size).allMatch(value -> value.id().startsWith("following-"));
    }

    @ParameterizedTest @ValueSource(ints = {4, 8, 20, 30, 50})
    void quarterReservationScalesWithPageSizeAndDoesNotRequireMissingContent(int size) {
        var pool = following();
        for (int index = 30; index < 75; index++) pool.add(followed(index));
        for (int index = 0; index < 25; index++) pool.add(artist("artist-" + index, 300_000));
        var page = mix(pool, size, MusicianFeedAnnouncementPlan.EMPTY, null);
        assertThat(page.items()).hasSize(size);
        assertThat(page.items().stream().filter(StudioFeedMixerTest::useful)).hasSize(size / 4);
        assertThat(mix(List.of(artist("artist-only", 300_000)), size, MusicianFeedAnnouncementPlan.EMPTY, null).items())
                .extracting(MusicianFeedItemResponse::id).containsExactly("artist-only");
    }

    @Test void announcementsCannotTrimRequestsOrTheQuarterOfUsefulContent() {
        var pool = following();
        for (int index = 0; index < 10; index++) pool.add(artist("artist-" + index, 300_000));
        pool.add(request("request-only", CollabWantedType.STUDIO, 10));
        var entries = new ArrayList<MusicianFeedAnnouncementPlan.Entry>();
        for (int index = 0; index < 3; index++) {
            var announcement = candidate("announcement-" + index, MusicianFeedItemType.ANNOUNCEMENT, "SYSTEM", 1,
                    MusicianFeedLane.SYSTEM, Map.of());
            pool.add(announcement);
            entries.add(new MusicianFeedAnnouncementPlan.Entry(announcement.target().id(), index == 0 ? 1 : 4));
        }
        var page = mix(pool, 20, new MusicianFeedAnnouncementPlan(entries), null);
        assertThat(page.items()).hasSize(20).anyMatch(value -> value.type() == MusicianFeedItemType.ANNOUNCEMENT);
        assertThat(page.items()).anyMatch(value -> value.id().equals("request-only"));
        assertThat(page.items().stream().filter(StudioFeedMixerTest::useful).count()).isGreaterThanOrEqualTo(5);
    }

    @Test void moduleSharesStaySparseAndDoNotStartThePageAfterAnotherModuleShare() {
        var pool = following();
        for (int index = 0; index < 8; index++) pool.add(candidate("module-" + index,
                index % 2 == 0 ? MusicianFeedItemType.TABLEGROUP_PROFILE_SHARE : MusicianFeedItemType.OVERTHINKING_PROFILE_SHARE,
                "LISTENER", 5_000_000, MusicianFeedLane.MODULE_SHARE, Map.of()));
        var page = mix(pool, 20, MusicianFeedAnnouncementPlan.EMPTY, null);
        assertThat(page.itemLanes().stream().filter(value -> value == MusicianFeedLane.MODULE_SHARE)).hasSize(2);
        for (int index = 1; index < page.itemLanes().size(); index++) {
            assertThat(page.itemLanes().get(index) == MusicianFeedLane.MODULE_SHARE
                    && page.itemLanes().get(index - 1) == MusicianFeedLane.MODULE_SHARE).isFalse();
        }
        assertThat(mix(pool, 20, MusicianFeedAnnouncementPlan.EMPTY, MusicianFeedLane.MODULE_SHARE).itemLanes())
                .doesNotContain(MusicianFeedLane.MODULE_SHARE);
    }

    @Test void studioHasNoCompletionAndSparseResultsEndWithoutDuplicates() {
        var pool = new ArrayList<MusicianFeedCandidate>();
        pool.add(candidate("completion", MusicianFeedItemType.PROFILE_COMPLETION, "STUDIO", 9_000_000,
                MusicianFeedLane.SYSTEM, Map.of()));
        pool.add(artist("artist-one", 300_000));
        pool.add(request("request-one", CollabWantedType.STUDIO, 600_000));
        var page = mix(pool, 20, MusicianFeedAnnouncementPlan.EMPTY, null);
        assertThat(page.items()).hasSize(2).noneMatch(value -> value.id().equals("completion"));
        assertThat(page.hasMore()).isFalse();
    }

    private MusicianFeedMixer.MixedPage mix(List<MusicianFeedCandidate> pool, int size,
                                             MusicianFeedAnnouncementPlan plan, MusicianFeedLane previousLane) {
        return mixer.mix(new UUID(1, 1), NOW, size, EnumSet.allOf(MusicianFeedItemType.class),
                MusicianFeedFeedbackSnapshot.empty(), pool, List.of(), null, 0, 0, false, null, previousLane,
                0, 0, 0, plan, null, BackstageFeedAudience.STUDIO);
    }

    private static boolean useful(MusicianFeedItemResponse item) {
        return item.id().startsWith("artist-") || item.id().startsWith("request-");
    }

    private static ArrayList<MusicianFeedCandidate> following() {
        return new ArrayList<>(IntStream.range(0, 30).mapToObj(StudioFeedMixerTest::followed).toList());
    }

    private static MusicianFeedCandidate followed(int index) {
        return candidate("following-" + index, MusicianFeedItemType.TRACK, "MUSICIAN", 1_000_000,
                MusicianFeedLane.FOLLOWING, Map.of());
    }

    private static MusicianFeedCandidate artist(String id, long score) {
        return candidate(id, MusicianFeedItemType.TRACK, "BAND", score, MusicianFeedLane.RELEVANT_OPPORTUNITY, Map.of());
    }

    private static MusicianFeedCandidate request(String id, CollabWantedType wantedType, long score) {
        var listing = new CollabListingResponse(UUID.randomUUID(), 0, CollabListingStatus.OPEN, null,
                CollabCadence.REGULAR, wantedType, null, wantedType == CollabWantedType.MUSICIAN ? CollabBranch.SOUND_ENGINEER : null,
                null, "Recording collaboration", "Request", null, List.of(), null, NOW.plusSeconds(3600), null,
                null, CollabFeeStatus.NOT_APPLICABLE, NOW, null, NOW, null, 0, false, false, false);
        return candidate(id, MusicianFeedItemType.COLLAB, "MUSICIAN", score,
                MusicianFeedLane.RELEVANT_OPPORTUNITY, new MusicianFeedPayloads.Collab(listing));
    }

    private static MusicianFeedCandidate candidate(String id, MusicianFeedItemType type, String authorType,
                                                   long score, MusicianFeedLane lane, Object payload) {
        UUID identity = UUID.nameUUIDFromBytes(id.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var author = new MusicianFeedItemResponse.Author(identity, identity, authorType, "user", "Name", null,
                lane == MusicianFeedLane.FOLLOWING);
        return new MusicianFeedCandidate(id, type, 1, NOW.minusSeconds(60),
                new MusicianFeedItemResponse.Reason(MusicianFeedReasonCode.DISCOVERY, List.of(), 0),
                author, new MusicianFeedItemResponse.Target(type.name(), identity), null, null, List.of(), payload,
                score, 0, lane, false);
    }

    private static MusicianFeedPayloads.ProfileMedia media(String kind) {
        return new MusicianFeedPayloads.ProfileMedia(new UUID(0, 7), kind, "https://media.test/view",
                "https://media.test/play", null, "Performance", null, 120, 1080, 720);
    }
}
