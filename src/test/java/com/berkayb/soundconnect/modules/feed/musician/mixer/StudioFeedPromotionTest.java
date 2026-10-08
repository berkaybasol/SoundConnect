package com.berkayb.soundconnect.modules.feed.musician.mixer;

import com.berkayb.soundconnect.modules.collab.dto.response.CollabActorSummary;
import com.berkayb.soundconnect.modules.collab.dto.response.CollabCitySummary;
import com.berkayb.soundconnect.modules.collab.dto.response.CollabListingResponse;
import com.berkayb.soundconnect.modules.collab.enums.*;
import com.berkayb.soundconnect.modules.feed.musician.api.*;
import com.berkayb.soundconnect.modules.feed.musician.announcement.MusicianFeedAnnouncementPlan;
import com.berkayb.soundconnect.modules.feed.musician.candidate.*;
import com.berkayb.soundconnect.modules.feed.musician.core.BackstageFeedAudience;
import com.berkayb.soundconnect.modules.feed.musician.feedback.MusicianFeedFeedbackSnapshot;
import com.berkayb.soundconnect.modules.feed.musician.sponsor.MusicianFeedPromotionCadence;
import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class StudioFeedPromotionTest {
    private static final UUID VIEWER = new UUID(1, 1);
    private static final Instant NOW = Instant.parse("2026-09-14T12:00:00Z");
    private final MusicianFeedMixer mixer = new MusicianFeedMixer();

    @Test void nativeStudioSponsorAndAnnouncementsPreserveTheQuarterAndBothUsefulSources() {
        var nativeRequest = studioRequest("dense", 3_000_000);
        var sponsor = promote(nativeRequest);
        List<MusicianFeedCandidate> organic = following(35);
        organic.add(nativeRequest);
        IntStream.range(0, 10).forEach(index -> organic.add(artist(index)));
        var entries = new ArrayList<MusicianFeedAnnouncementPlan.Entry>();
        for (int index = 0; index < 3; index++) {
            UUID announcementId = id("announcement-" + index);
            organic.add(new MusicianFeedCandidate("ANNOUNCEMENT:" + announcementId, MusicianFeedItemType.ANNOUNCEMENT,
                    1, NOW.minusSeconds(60), reason(MusicianFeedReasonCode.PLATFORM_ANNOUNCEMENT), null,
                    new MusicianFeedItemResponse.Target("ANNOUNCEMENT", announcementId), null, null,
                    List.of(MusicianFeedFeedbackAction.HIDE), Map.of("id", announcementId),
                    0, 0, MusicianFeedLane.SYSTEM, false));
            entries.add(new MusicianFeedAnnouncementPlan.Entry(announcementId, index == 0 ? 1 : 4));
        }
        var plan = new MusicianFeedAnnouncementPlan(entries);

        var page = mix(organic, List.of(sponsor), 20, 0, plan);

        assertThat(page.items()).hasSize(20).anyMatch(item -> item.type() == MusicianFeedItemType.ANNOUNCEMENT);
        assertThat(page.items().stream().filter(StudioFeedPromotionTest::useful).count()).isGreaterThanOrEqualTo(5);
        assertThat(page.items()).anyMatch(StudioFeedPromotionTest::artistDiscovery);
        assertThat(page.items().stream().filter(item -> item.target().equals(nativeRequest.target())))
                .singleElement().satisfies(item -> {
                    assertThat(item.id()).isEqualTo(sponsor.itemId());
                    assertThat(item.type()).isEqualTo(MusicianFeedItemType.COLLAB);
                    assertThat(item.promotion()).isEqualTo(sponsor.promotion());
                    assertThat(item.payload()).isEqualTo(nativeRequest.payload());
                    assertThat(((MusicianFeedPayloads.Collab) item.payload()).listing().wantedType())
                            .isEqualTo(CollabWantedType.STUDIO);
                });
        assertThat(page.items()).noneMatch(item -> item.id().equals(nativeRequest.itemId()));
        assertNoDuplicateTargets(page.items());
        for (int index = 1; index < page.items().size(); index++) {
            assertThat(isInserted(page.items().get(index - 1)) && isInserted(page.items().get(index)))
                    .as("A sponsor and an announcement need an organic separator").isFalse();
        }
        assertThat(mix(organic, List.of(sponsor), 20, 0, plan)).isEqualTo(page);
    }

    @Test void deferredNativeStudioRequestUpgradesAtCadenceWithoutLosingArtistOrInventingAnotherPage() {
        int gap = MusicianFeedPromotionCadence.organicGap(VIEWER, NOW, 0);
        var nativeRequest = studioRequest("sparse", 3_000_000);
        var sponsor = promote(nativeRequest);
        List<MusicianFeedCandidate> organic = following(gap);
        // This request outranks every other candidate, so it is selected before the
        // cadence is due and must be deferred until its sponsored presentation fits.
        organic.add(nativeRequest);
        organic.add(artist(99));

        var page = mix(organic, List.of(sponsor), gap + 2, 0, MusicianFeedAnnouncementPlan.EMPTY);

        assertThat(page.items()).hasSize(gap + 2).anyMatch(StudioFeedPromotionTest::artistDiscovery);
        assertThat(page.items().stream().filter(item -> item.target().equals(nativeRequest.target())))
                .singleElement().satisfies(item -> assertThat(item.promotion()).isEqualTo(sponsor.promotion()));
        assertThat(page.items().get(gap).id()).isEqualTo(sponsor.itemId());
        assertThat(page.items().subList(0, gap)).allMatch(item -> item.promotion() == null);
        assertThat(page.items().stream().filter(StudioFeedPromotionTest::useful)).hasSize(2);
        assertNoDuplicateTargets(page.items());
        assertThat(page.hasMore()).as("The promoted native target is already consumed").isFalse();
    }

    @ParameterizedTest @ValueSource(ints = {1, 2, 3, 4, 20})
    void dueSponsorOnSmallOrSparsePageRepresentsItsOnlyNativeStudioTargetOnce(int pageSize) {
        var nativeRequest = studioRequest("only", 3_000_000);
        var sponsor = promote(nativeRequest);
        int deliveredOrganic = MusicianFeedPromotionCadence.organicGap(VIEWER, NOW, 0);

        var page = mix(List.of(nativeRequest), List.of(sponsor), pageSize, deliveredOrganic,
                MusicianFeedAnnouncementPlan.EMPTY);

        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo(sponsor.itemId());
            assertThat(item.target()).isEqualTo(nativeRequest.target());
            assertThat(item.promotion()).isEqualTo(sponsor.promotion());
            assertThat(item.payload()).isEqualTo(nativeRequest.payload());
        });
        assertThat(page.hasMore()).isFalse();
        assertThat(page.cursorBoundary()).isNotNull();
    }

    private MusicianFeedMixer.MixedPage mix(List<MusicianFeedCandidate> organic, List<MusicianFeedCandidate> sponsors,
                                             int size, long deliveredOrganic, MusicianFeedAnnouncementPlan plan) {
        return mixer.mix(VIEWER, NOW, size, EnumSet.allOf(MusicianFeedItemType.class),
                MusicianFeedFeedbackSnapshot.empty(), organic, sponsors, null, deliveredOrganic, 0, false,
                null, null, 0, 0, 0, plan, null, BackstageFeedAudience.STUDIO);
    }

    private static List<MusicianFeedCandidate> following(int count) {
        return new ArrayList<>(IntStream.range(0, count).mapToObj(index -> track("following-" + index,
                true, 1_000_000 - index)).toList());
    }

    private static MusicianFeedCandidate artist(int index) { return track("artist-" + index, false, 300_000); }

    private static MusicianFeedCandidate track(String name, boolean followed, long score) {
        UUID target = id("media-" + name);
        var author = author(name, followed);
        return new MusicianFeedCandidate("TRACK:" + target, MusicianFeedItemType.TRACK, 1, NOW.minusSeconds(60),
                reason(followed ? MusicianFeedReasonCode.FOLLOWING_PUBLICATION : MusicianFeedReasonCode.DISCOVERY),
                author, new MusicianFeedItemResponse.Target("MEDIA", target), null, null,
                List.of(MusicianFeedFeedbackAction.HIDE), new MusicianFeedPayloads.Track(id("track-" + name), target,
                "Recording", "https://media.test/recording.mp3", 120, null), score, 0,
                followed ? MusicianFeedLane.FOLLOWING : MusicianFeedLane.RELEVANT_OPPORTUNITY, false);
    }

    private static MusicianFeedCandidate studioRequest(String name, long score) {
        UUID target = id("collab-" + name);
        var author = author(name, false);
        var publisher = new CollabActorSummary(id("actor-" + name), ProfileType.MUSICIAN,
                author.profileId(), author.userId(), author.username(), author.displayName(), null,
                BigDecimal.ZERO, 0, 0);
        var listing = new CollabListingResponse(target, 0, CollabListingStatus.OPEN, null,
                CollabCadence.EXTRA, CollabWantedType.STUDIO, null, null, null,
                "Kayıt için stüdyo arıyoruz", "Yeni şarkılarımızı kaydetmek istiyoruz.",
                new CollabCitySummary(id("city"), "İstanbul"), List.of("Rock"), NOW.plusSeconds(1800),
                NOW.plusSeconds(3600), null, null, CollabFeeStatus.UNSPECIFIED, NOW.minusSeconds(60),
                null, NOW.minusSeconds(60), publisher, 0, false, false, false);
        return new MusicianFeedCandidate("COLLAB:" + target, MusicianFeedItemType.COLLAB, 1, NOW.minusSeconds(60),
                reason(MusicianFeedReasonCode.CITY_MATCH), author, new MusicianFeedItemResponse.Target("COLLAB", target),
                null, null, List.of(MusicianFeedFeedbackAction.HIDE), new MusicianFeedPayloads.Collab(listing),
                score, 260_000, MusicianFeedLane.RELEVANT_OPPORTUNITY, false);
    }

    private static MusicianFeedCandidate promote(MusicianFeedCandidate nativeRequest) {
        var campaign = id("campaign-" + nativeRequest.target().id());
        return new MusicianFeedCandidate("COLLAB:PROMOTED:" + campaign, nativeRequest.type(), nativeRequest.payloadVersion(),
                nativeRequest.occurredAt(), reason(MusicianFeedReasonCode.FEATURED), nativeRequest.author(),
                nativeRequest.target(), nativeRequest.engagement(),
                new MusicianFeedItemResponse.Promotion(campaign, "Öne çıkarılan", "İlana git",
                        "/is-birligi/ilan/" + nativeRequest.target().id()),
                nativeRequest.feedbackCapabilities(), nativeRequest.payload(), 4_000_000, 0,
                MusicianFeedLane.RELEVANT_OPPORTUNITY, false);
    }

    private static MusicianFeedItemResponse.Author author(String name, boolean followed) {
        return new MusicianFeedItemResponse.Author(id("user-" + name), id("profile-" + name), "MUSICIAN",
                "musician", "Müzisyen", null, followed);
    }

    private static MusicianFeedItemResponse.Reason reason(MusicianFeedReasonCode code) {
        return new MusicianFeedItemResponse.Reason(code, List.of(), 0);
    }

    private static boolean useful(MusicianFeedItemResponse item) {
        return artistDiscovery(item) || item.type() == MusicianFeedItemType.COLLAB
                && item.payload() instanceof MusicianFeedPayloads.Collab collab
                && collab.listing().wantedType() == CollabWantedType.STUDIO;
    }

    private static boolean artistDiscovery(MusicianFeedItemResponse item) {
        return item.type() == MusicianFeedItemType.TRACK && item.author() != null
                && !item.author().followedByViewer()
                && Set.of("MUSICIAN", "BAND").contains(item.author().profileType());
    }

    private static boolean isInserted(MusicianFeedItemResponse item) {
        return item.promotion() != null || item.type() == MusicianFeedItemType.ANNOUNCEMENT;
    }

    private static void assertNoDuplicateTargets(List<MusicianFeedItemResponse> items) {
        assertThat(items).extracting(MusicianFeedItemResponse::id).doesNotHaveDuplicates();
        assertThat(items).extracting(item -> item.target().type() + ":" + item.target().id()).doesNotHaveDuplicates();
    }

    private static UUID id(String value) { return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)); }
}
