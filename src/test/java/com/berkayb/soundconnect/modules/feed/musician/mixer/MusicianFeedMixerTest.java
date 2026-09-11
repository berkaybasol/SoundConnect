package com.berkayb.soundconnect.modules.feed.musician.mixer;

import com.berkayb.soundconnect.modules.feed.musician.api.*;
import com.berkayb.soundconnect.modules.feed.musician.candidate.MusicianFeedCandidate;
import com.berkayb.soundconnect.modules.feed.musician.candidate.MusicianFeedLane;
import com.berkayb.soundconnect.modules.feed.musician.feedback.MusicianFeedFeedbackSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class MusicianFeedMixerTest {
    private static final Instant ANCHOR = Instant.parse("2026-09-11T12:00:00Z");
    private static final UUID VIEWER = UUID.randomUUID();
    private final MusicianFeedMixer mixer = new MusicianFeedMixer();

    @Test
    void filtersOwnershipFeedbackAndUnsupportedTypesThenDeduplicatesNativeTargets() {
        UUID muted = UUID.randomUUID();
        UUID sharedTarget = UUID.randomUUID();
        MusicianFeedCandidate kept = candidate("TRACK:kept", MusicianFeedItemType.TRACK,
                UUID.randomUUID(), sharedTarget, 900_000, false, null);
        List<MusicianFeedCandidate> candidates = List.of(
                kept,
                candidate("TRACK:duplicate", MusicianFeedItemType.TRACK, UUID.randomUUID(), sharedTarget,
                        100_000, false, null),
                candidate("TRACK:hidden", MusicianFeedItemType.TRACK, UUID.randomUUID(), UUID.randomUUID(),
                        2_000_000, false, null),
                candidate("TRACK:muted", MusicianFeedItemType.TRACK, muted, UUID.randomUUID(),
                        2_000_000, false, null),
                candidate("TRACK:own-flag", MusicianFeedItemType.TRACK, UUID.randomUUID(), UUID.randomUUID(),
                        2_000_000, true, null),
                candidate("TRACK:self", MusicianFeedItemType.TRACK, VIEWER, UUID.randomUUID(),
                        2_000_000, false, null),
                candidate("COLLAB:unsupported", MusicianFeedItemType.COLLAB, UUID.randomUUID(), UUID.randomUUID(),
                        2_000_000, false, null));
        UUID mutedProfile = candidates.get(3).author().profileId();
        MusicianFeedFeedbackSnapshot feedback = new MusicianFeedFeedbackSnapshot(
                Set.of("TRACK:hidden"),
                Set.of(MusicianFeedFeedbackSnapshot.authorKey("MUSICIAN", mutedProfile)), Map.of());

        var page = mixer.mix(VIEWER, ANCHOR, 20, Set.of(MusicianFeedItemType.TRACK), feedback,
                candidates, List.of(), null, 0);

        assertThat(page.items()).extracting(MusicianFeedItemResponse::id).containsExactly("TRACK:kept");
    }

    @Test
    void diversityReordersOnlyInsideTheSelectedStableKeysetWindow() {
        UUID authorA = UUID.randomUUID();
        UUID authorB = UUID.randomUUID();
        List<MusicianFeedCandidate> candidates = List.of(
                candidate("TRACK:a1", MusicianFeedItemType.TRACK, authorA, UUID.randomUUID(), 1_000_000, false, null),
                candidate("TRACK:a2", MusicianFeedItemType.TRACK, authorA, UUID.randomUUID(), 999_000, false, null),
                candidate("PROFILE_MEDIA:b", MusicianFeedItemType.PROFILE_MEDIA, authorB, UUID.randomUUID(),
                        995_000, false, null));
        Set<MusicianFeedItemType> types = Set.of(MusicianFeedItemType.TRACK, MusicianFeedItemType.PROFILE_MEDIA);

        var first = mixer.mix(VIEWER, ANCHOR, 3, types, MusicianFeedFeedbackSnapshot.empty(),
                candidates, List.of(), null, 0);
        var second = mixer.mix(VIEWER, ANCHOR, 3, types, MusicianFeedFeedbackSnapshot.empty(),
                candidates, List.of(), null, 0);

        assertThat(first.items()).extracting(MusicianFeedItemResponse::id)
                .containsExactly("TRACK:a1", "PROFILE_MEDIA:b", "TRACK:a2");
        assertThat(second).isEqualTo(first);
        assertThat(first.cursorBoundary().itemId()).isEqualTo("PROFILE_MEDIA:b");
    }

    @Test
    void commentsStayIndividualWhileLikeStoriesOnTheSameTargetCollapse() {
        UUID target = UUID.randomUUID();
        List<MusicianFeedCandidate> candidates = List.of(
                candidate("ACTIVITY_COMMENT:c1", MusicianFeedItemType.ACTIVITY_COMMENT,
                        UUID.randomUUID(), target, 900_000, false, null),
                candidate("ACTIVITY_COMMENT:c2", MusicianFeedItemType.ACTIVITY_COMMENT,
                        UUID.randomUUID(), target, 890_000, false, null),
                candidate("ACTIVITY_LIKE:l1", MusicianFeedItemType.ACTIVITY_LIKE,
                        UUID.randomUUID(), target, 880_000, false, null),
                candidate("ACTIVITY_LIKE:l2", MusicianFeedItemType.ACTIVITY_LIKE,
                        UUID.randomUUID(), target, 870_000, false, null));

        var page = mixer.mix(VIEWER, ANCHOR, 10,
                Set.of(MusicianFeedItemType.ACTIVITY_COMMENT, MusicianFeedItemType.ACTIVITY_LIKE),
                MusicianFeedFeedbackSnapshot.empty(), candidates, List.of(), null, 0);

        assertThat(page.items()).extracting(MusicianFeedItemResponse::id)
                .contains("ACTIVITY_COMMENT:c1", "ACTIVITY_COMMENT:c2")
                .containsAnyOf("ACTIVITY_LIKE:l1", "ACTIVITY_LIKE:l2")
                .hasSize(3);
    }

    @Test
    void promotionsAreNeverEarlyOrConsecutiveAndUseOnePerEightOrganicItems() {
        List<MusicianFeedCandidate> organic = IntStream.range(0, 24)
                .mapToObj(index -> candidate("TRACK:" + index, MusicianFeedItemType.TRACK,
                        UUID.randomUUID(), UUID.randomUUID(), 1_000_000 - index, false, null))
                .toList();
        List<MusicianFeedCandidate> sponsors = IntStream.range(0, 3)
                .mapToObj(index -> candidate("SPONSORED:" + index, MusicianFeedItemType.SPONSORED,
                        UUID.randomUUID(), UUID.randomUUID(), 2_000_000 - index, false,
                        new MusicianFeedItemResponse.Promotion(UUID.randomUUID(), "Sponsored", "Aç", "https://example.test")))
                .toList();

        var page = mixer.mix(VIEWER, ANCHOR, 20,
                Set.of(MusicianFeedItemType.TRACK, MusicianFeedItemType.SPONSORED),
                MusicianFeedFeedbackSnapshot.empty(), organic, sponsors, null, 0);

        List<Integer> promotedPositions = IntStream.range(0, page.items().size())
                .filter(index -> page.items().get(index).promotion() != null).boxed().toList();
        assertThat(promotedPositions).containsExactly(8, 17);
        assertThat(page.items().subList(0, 2)).allMatch(item -> item.promotion() == null);
        assertThat(page.deliveredOrganicCount()).isEqualTo(18);
        assertThat(page.hasMore()).isTrue();
    }

    @Test
    void commentIndividualityNeverAllowsASponsorForItsUnderlyingOrganicTarget() {
        UUID target = UUID.randomUUID();
        var comment = candidate("ACTIVITY_COMMENT:c1", MusicianFeedItemType.ACTIVITY_COMMENT,
                UUID.randomUUID(), target, 900_000, false, null);
        var duplicateSponsor = candidate("SPONSORED:s1", MusicianFeedItemType.SPONSORED,
                UUID.randomUUID(), target, 2_000_000, false,
                new MusicianFeedItemResponse.Promotion(UUID.randomUUID(), "Sponsored", "Aç", "/open"));
        List<MusicianFeedCandidate> organic = new ArrayList<>();
        organic.add(comment);
        IntStream.range(0, 12).forEach(index -> organic.add(candidate("TRACK:x" + index,
                MusicianFeedItemType.TRACK, UUID.randomUUID(), UUID.randomUUID(),
                800_000 - index, false, null)));

        var page = mixer.mix(VIEWER, ANCHOR, 12,
                Set.of(MusicianFeedItemType.ACTIVITY_COMMENT, MusicianFeedItemType.TRACK,
                        MusicianFeedItemType.SPONSORED), MusicianFeedFeedbackSnapshot.empty(),
                organic, List.of(duplicateSponsor), null, 0);

        assertThat(page.items()).noneMatch(item -> item.promotion() != null);
    }

    @Test
    void suppressedCommentTailCannotAdvertiseASecondSponsorContinuation() {
        UUID firstTarget = UUID.randomUUID();
        List<MusicianFeedCandidate> comments = IntStream.range(0, 8)
                .mapToObj(index -> candidate("ACTIVITY_COMMENT:" + index,
                        MusicianFeedItemType.ACTIVITY_COMMENT, UUID.randomUUID(), firstTarget,
                        900_000 - index, false, null))
                .toList();
        var firstSponsor = candidate("SPONSORED:first", MusicianFeedItemType.SPONSORED,
                UUID.randomUUID(), firstTarget, 2_000_000, false,
                new MusicianFeedItemResponse.Promotion(UUID.randomUUID(), "Sponsored", "Aç", "/first"));
        var secondSponsor = candidate("SPONSORED:second", MusicianFeedItemType.SPONSORED,
                UUID.randomUUID(), UUID.randomUUID(), 1_900_000, false,
                new MusicianFeedItemResponse.Promotion(UUID.randomUUID(), "Sponsored", "Aç", "/second"));

        var page = mixer.mix(VIEWER, ANCHOR, 1,
                Set.of(MusicianFeedItemType.ACTIVITY_COMMENT, MusicianFeedItemType.SPONSORED),
                MusicianFeedFeedbackSnapshot.empty(), comments, List.of(firstSponsor, secondSponsor),
                null, 8, 0, false);

        assertThat(page.items()).singleElement().satisfies(value ->
                assertThat(value.id()).isEqualTo("SPONSORED:first"));
        assertThat(page.hasMore()).isFalse();
    }

    @Test
    void exactCadenceThresholdKeepsAContinuationForTheWaitingSponsor() {
        List<MusicianFeedCandidate> organic = IntStream.range(0, 8)
                .mapToObj(index -> candidate("TRACK:" + index, MusicianFeedItemType.TRACK,
                        UUID.randomUUID(), UUID.randomUUID(), 1_000_000 - index, false, null))
                .toList();
        var sponsor = candidate("SPONSORED:one", MusicianFeedItemType.SPONSORED,
                UUID.randomUUID(), UUID.randomUUID(), 2_000_000, false,
                new MusicianFeedItemResponse.Promotion(UUID.randomUUID(), "Sponsored", "Aç", "/open"));

        var thresholdPage = mixer.mix(VIEWER, ANCHOR, 8,
                Set.of(MusicianFeedItemType.TRACK, MusicianFeedItemType.SPONSORED),
                MusicianFeedFeedbackSnapshot.empty(), organic, List.of(sponsor), null, 0, 0, false);
        assertThat(thresholdPage.items()).allMatch(value -> value.promotion() == null);
        assertThat(thresholdPage.hasMore()).isTrue();
        assertThat(thresholdPage.cursorBoundary()).isNotNull();

        var sponsorPage = mixer.mix(VIEWER, ANCHOR, 1,
                Set.of(MusicianFeedItemType.TRACK, MusicianFeedItemType.SPONSORED),
                MusicianFeedFeedbackSnapshot.empty(), List.of(), List.of(sponsor), null,
                thresholdPage.deliveredOrganicCount(), 0, false);
        assertThat(sponsorPage.items()).singleElement().satisfies(value ->
                assertThat(value.promotion()).isNotNull());
        assertThat(sponsorPage.cursorBoundary()).isNotNull();
        assertThat(sponsorPage.hasMore()).isFalse();
    }

    @Test
    void nativeCampaignUpgradesItsOrganicTargetAtTheSlotWithoutDuplicatingIt() {
        UUID target = UUID.randomUUID();
        List<MusicianFeedCandidate> organic = new ArrayList<>();
        IntStream.range(0, 8).forEach(index -> organic.add(candidate("TRACK:" + index,
                MusicianFeedItemType.TRACK, UUID.randomUUID(), UUID.randomUUID(),
                1_000_000 - index, false, null)));
        organic.add(candidate("COLLAB:organic", MusicianFeedItemType.COLLAB,
                UUID.randomUUID(), target, 900_000, false, null));
        var promotedNative = candidate("COLLAB:promoted", MusicianFeedItemType.COLLAB,
                UUID.randomUUID(), target, 2_000_000, false,
                new MusicianFeedItemResponse.Promotion(UUID.randomUUID(), "Sponsored", "Başvur", "/collab"));

        var page = mixer.mix(VIEWER, ANCHOR, 10,
                Set.of(MusicianFeedItemType.TRACK, MusicianFeedItemType.COLLAB),
                MusicianFeedFeedbackSnapshot.empty(), organic, List.of(promotedNative), null, 0, 0, false);

        assertThat(page.items().stream().filter(value -> value.target().id().equals(target))).singleElement()
                .satisfies(value -> assertThat(value.promotion()).isNotNull());
    }

    @Test
    void promotedDeferredNativeTargetDoesNotCreateAPhantomContinuation() {
        UUID target = UUID.randomUUID();
        List<MusicianFeedCandidate> organic = new ArrayList<>();
        organic.add(candidate("COLLAB:organic", MusicianFeedItemType.COLLAB,
                UUID.randomUUID(), target, 2_000_000, false, null));
        IntStream.range(0, 8).forEach(index -> organic.add(candidate("TRACK:" + index,
                MusicianFeedItemType.TRACK, UUID.randomUUID(), UUID.randomUUID(),
                1_000_000 - index, false, null)));
        var promotedNative = candidate("COLLAB:promoted", MusicianFeedItemType.COLLAB,
                UUID.randomUUID(), target, 2_100_000, false,
                new MusicianFeedItemResponse.Promotion(UUID.randomUUID(), "Sponsored", "Başvur", "/collab"));

        var page = mixer.mix(VIEWER, ANCHOR, 9,
                Set.of(MusicianFeedItemType.TRACK, MusicianFeedItemType.COLLAB),
                MusicianFeedFeedbackSnapshot.empty(), organic, List.of(promotedNative), null,
                0, 0, false);

        assertThat(page.items()).hasSize(9);
        assertThat(page.items().getLast().promotion()).isNotNull();
        assertThat(page.hasMore()).isFalse();
    }

    @Test
    void promotedUnexaminedNativeTailDoesNotCreateAPhantomContinuation() {
        UUID target = UUID.randomUUID();
        List<MusicianFeedCandidate> organic = new ArrayList<>();
        IntStream.range(0, 8).forEach(index -> organic.add(candidate("TRACK:" + index,
                MusicianFeedItemType.TRACK, UUID.randomUUID(), UUID.randomUUID(),
                2_000_000 - index, false, null)));
        organic.add(candidate("COLLAB:organic-tail", MusicianFeedItemType.COLLAB,
                UUID.randomUUID(), target, 100_000, false, null));
        var promotedNative = candidate("COLLAB:promoted", MusicianFeedItemType.COLLAB,
                UUID.randomUUID(), target, 2_100_000, false,
                new MusicianFeedItemResponse.Promotion(UUID.randomUUID(), "Sponsored", "Başvur", "/collab"));

        var page = mixer.mix(VIEWER, ANCHOR, 9,
                Set.of(MusicianFeedItemType.TRACK, MusicianFeedItemType.COLLAB),
                MusicianFeedFeedbackSnapshot.empty(), organic, List.of(promotedNative), null,
                0, 0, false);

        assertThat(page.items()).hasSize(9);
        assertThat(page.items().getLast().promotion()).isNotNull();
        assertThat(page.hasMore()).isFalse();
    }

    @Test
    void generalDiscoveryAndListenerModuleSharesHaveExplicitSparseBudgets() {
        List<MusicianFeedCandidate> candidates = new ArrayList<>();
        IntStream.range(0, 20).forEach(index -> candidates.add(candidateWithLane(
                "PROFILE:" + index, MusicianFeedItemType.PROFILE, UUID.randomUUID(), UUID.randomUUID(),
                400_000 - index, MusicianFeedLane.GENERAL_DISCOVERY)));
        IntStream.range(0, 20).forEach(index -> candidates.add(candidateWithLane(
                "OVERTHINKING_PROFILE_SHARE:" + index, MusicianFeedItemType.OVERTHINKING_PROFILE_SHARE,
                UUID.randomUUID(), UUID.randomUUID(), 900_000 - index, MusicianFeedLane.MODULE_SHARE)));
        IntStream.range(0, 20).forEach(index -> candidates.add(candidateWithLane(
                "ACTIVITY_LIKE:module:" + index, MusicianFeedItemType.ACTIVITY_LIKE,
                UUID.randomUUID(), UUID.randomUUID(), 850_000 - index, MusicianFeedLane.MODULE_SHARE)));

        var page = mixer.mix(VIEWER, ANCHOR, 20,
                Set.of(MusicianFeedItemType.PROFILE, MusicianFeedItemType.OVERTHINKING_PROFILE_SHARE,
                        MusicianFeedItemType.ACTIVITY_LIKE),
                MusicianFeedFeedbackSnapshot.empty(), candidates, List.of(), null, 0, 0, false, null);

        assertThat(page.items()).hasSizeLessThanOrEqualTo(4);
        assertThat(page.items().stream().filter(value -> value.type() == MusicianFeedItemType.PROFILE).count())
                .isLessThanOrEqualTo(2);
        Set<MusicianFeedItemType> moduleTypes = Set.of(
                MusicianFeedItemType.OVERTHINKING_PROFILE_SHARE, MusicianFeedItemType.ACTIVITY_LIKE);
        assertThat(page.items().stream().filter(value -> moduleTypes.contains(value.type())).count())
                .isLessThanOrEqualTo(2);
        for (int index = 1; index < page.items().size(); index++) {
            boolean previousModule = moduleTypes.contains(page.items().get(index - 1).type());
            boolean currentModule = moduleTypes.contains(page.items().get(index).type());
            assertThat(previousModule && currentModule).isFalse();
        }
    }

    @Test
    void moduleActivityLaneIsRememberedAcrossPageBoundaries() {
        var activity = candidateWithLane("ACTIVITY_LIKE:module", MusicianFeedItemType.ACTIVITY_LIKE,
                UUID.randomUUID(), UUID.randomUUID(), 900_000, MusicianFeedLane.MODULE_SHARE);
        var first = mixer.mix(VIEWER, ANCHOR, 1,
                Set.of(MusicianFeedItemType.ACTIVITY_LIKE), MusicianFeedFeedbackSnapshot.empty(),
                List.of(activity), List.of(), null, 0, 0, false, null,
                null, 0);
        assertThat(first.itemLanes()).containsExactly(MusicianFeedLane.MODULE_SHARE);

        var nextModule = candidateWithLane("ACTIVITY_LIKE:next", MusicianFeedItemType.ACTIVITY_LIKE,
                UUID.randomUUID(), UUID.randomUUID(), 800_000, MusicianFeedLane.MODULE_SHARE);
        var primary = candidateWithLane("TRACK:separator", MusicianFeedItemType.TRACK,
                UUID.randomUUID(), UUID.randomUUID(), 700_000, MusicianFeedLane.FOLLOWING);
        var second = mixer.mix(VIEWER, ANCHOR, 2,
                Set.of(MusicianFeedItemType.ACTIVITY_LIKE, MusicianFeedItemType.TRACK),
                MusicianFeedFeedbackSnapshot.empty(), List.of(nextModule, primary), List.of(), null,
                first.deliveredOrganicCount(), 0, false, MusicianFeedItemType.ACTIVITY_LIKE,
                first.itemLanes().getLast(), 0);

        assertThat(second.items()).extracting(MusicianFeedItemResponse::id).containsExactly("TRACK:separator");
    }

    @Test
    void authorDiversityUsesStableProfileIdentityRatherThanOwnerUserId() {
        UUID sharedOwner = UUID.randomUUID();
        UUID venueOne = UUID.randomUUID();
        UUID venueTwo = UUID.randomUUID();
        var first = candidateWithAuthor("PROFILE:v1", MusicianFeedItemType.PROFILE,
                sharedOwner, venueOne, "VENUE", UUID.randomUUID(), 1_000_000);
        var second = candidateWithAuthor("PROFILE:v2", MusicianFeedItemType.PROFILE,
                sharedOwner, venueTwo, "VENUE", UUID.randomUUID(), 999_000);

        var page = mixer.mix(VIEWER, ANCHOR, 2, Set.of(MusicianFeedItemType.PROFILE),
                MusicianFeedFeedbackSnapshot.empty(), List.of(first, second), List.of(), null, 0);

        assertThat(page.items()).extracting(MusicianFeedItemResponse::id)
                .containsExactly("PROFILE:v1", "PROFILE:v2");
    }

    @Test
    void sameTargetSocialProofKeepsNativePayloadAndAggregatesTheStrongestReason() {
        UUID target = UUID.randomUUID();
        UUID publisherUser = UUID.randomUUID();
        UUID publisherProfile = UUID.randomUUID();
        UUID actorUser = UUID.randomUUID();
        UUID actorProfile = UUID.randomUUID();
        var publisher = new MusicianFeedItemResponse.Author(publisherUser, publisherProfile,
                "MUSICIAN", "publisher", "Publisher", null, true);
        var actor = new MusicianFeedItemResponse.Author(actorUser, actorProfile,
                "LISTENER", "listener", "Listener", null, true);
        var nativeItem = new MusicianFeedCandidate("TRACK:native", MusicianFeedItemType.TRACK, 1,
                ANCHOR.minusSeconds(60), new MusicianFeedItemResponse.Reason(
                MusicianFeedReasonCode.FOLLOWING_PUBLICATION, List.of(publisher), 0), publisher,
                new MusicianFeedItemResponse.Target("MEDIA", target), null, null,
                List.of(MusicianFeedFeedbackAction.HIDE), Map.of("trackId", target),
                900_000, 0, MusicianFeedLane.FOLLOWING, false);
        var likeStory = new MusicianFeedCandidate("ACTIVITY_LIKE:one", MusicianFeedItemType.ACTIVITY_LIKE, 1,
                ANCHOR.minusSeconds(30), new MusicianFeedItemResponse.Reason(
                MusicianFeedReasonCode.FOLLOWED_USER_LIKED, List.of(actor), 0), actor,
                new MusicianFeedItemResponse.Target("MEDIA", target), null, null,
                List.of(MusicianFeedFeedbackAction.HIDE), Map.of("action", "LIKE"),
                1_000_000, 0, MusicianFeedLane.FOLLOWING, false);

        var page = mixer.mix(VIEWER, ANCHOR, 10,
                Set.of(MusicianFeedItemType.TRACK, MusicianFeedItemType.ACTIVITY_LIKE),
                MusicianFeedFeedbackSnapshot.empty(), List.of(nativeItem, likeStory), List.of(), null, 0);

        assertThat(page.items()).singleElement().satisfies(value -> {
            assertThat(value.type()).isEqualTo(MusicianFeedItemType.TRACK);
            assertThat(value.author().profileId()).isEqualTo(publisherProfile);
            assertThat(value.reason().code()).isEqualTo(MusicianFeedReasonCode.FOLLOWED_USER_LIKED);
            assertThat(value.reason().actors()).extracting(MusicianFeedItemResponse.Author::profileId)
                    .contains(actorProfile, publisherProfile);
            assertThat(value.payload()).isEqualTo(Map.of("trackId", target));
        });
    }

    private static MusicianFeedCandidate candidate(
            String id,
            MusicianFeedItemType type,
            UUID authorId,
            UUID targetId,
            long baseScore,
            boolean owned,
            MusicianFeedItemResponse.Promotion promotion
    ) {
        var author = new MusicianFeedItemResponse.Author(authorId, UUID.randomUUID(), "MUSICIAN",
                "user", "User", null, true);
        return new MusicianFeedCandidate(id, type, 1, ANCHOR.minusSeconds(60),
                new MusicianFeedItemResponse.Reason(MusicianFeedReasonCode.FOLLOWING_PUBLICATION,
                        List.of(author), 0), author,
                new MusicianFeedItemResponse.Target("MEDIA", targetId), null, promotion,
                List.of(MusicianFeedFeedbackAction.HIDE), Map.of("id", id),
                baseScore, 0, MusicianFeedLane.FOLLOWING, owned);
    }

    private static MusicianFeedCandidate candidateWithLane(
            String id, MusicianFeedItemType type, UUID authorId, UUID targetId,
            long baseScore, MusicianFeedLane lane) {
        MusicianFeedCandidate value = candidate(id, type, authorId, targetId, baseScore, false, null);
        return new MusicianFeedCandidate(value.itemId(), value.type(), value.payloadVersion(),
                value.occurredAt(), value.reason(), value.author(), value.target(), value.engagement(),
                value.promotion(), value.feedbackCapabilities(), value.payload(), value.baseScore(),
                value.relevanceScore(), lane, value.ownedByViewer());
    }

    private static MusicianFeedCandidate candidateWithAuthor(
            String id, MusicianFeedItemType type, UUID userId, UUID profileId,
            String profileType, UUID targetId, long score) {
        var author = new MusicianFeedItemResponse.Author(userId, profileId, profileType,
                "owner", "Owner", null, false);
        return new MusicianFeedCandidate(id, type, 1, ANCHOR.minusSeconds(60),
                new MusicianFeedItemResponse.Reason(MusicianFeedReasonCode.DISCOVERY, List.of(), 0),
                author, new MusicianFeedItemResponse.Target("PROFILE", targetId), null, null,
                List.of(MusicianFeedFeedbackAction.HIDE), Map.of("id", id), score, 0,
                MusicianFeedLane.FOLLOWING, false);
    }
}
