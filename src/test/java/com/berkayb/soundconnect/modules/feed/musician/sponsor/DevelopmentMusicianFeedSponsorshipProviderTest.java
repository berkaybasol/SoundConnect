package com.berkayb.soundconnect.modules.feed.musician.sponsor;

import com.berkayb.soundconnect.modules.collab.dto.response.CollabListingResponse;
import com.berkayb.soundconnect.modules.event.dto.response.EventResponseDto;
import com.berkayb.soundconnect.modules.feed.musician.api.*;
import com.berkayb.soundconnect.modules.feed.musician.candidate.*;
import com.berkayb.soundconnect.modules.feed.musician.delivery.MusicianFeedDeliverySnapshot;
import com.berkayb.soundconnect.modules.feed.musician.feedback.MusicianFeedFeedbackSnapshot;
import com.berkayb.soundconnect.modules.feed.musician.personalization.MusicianFeedPersonalizationSnapshot;
import com.berkayb.soundconnect.modules.feed.musician.provider.MusicianFeedCollabCandidateProvider;
import com.berkayb.soundconnect.modules.feed.musician.provider.MusicianFeedEventCandidateProvider;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DevelopmentMusicianFeedSponsorshipProviderTest {
    private static final Instant NOW = Instant.parse("2026-09-12T12:00:00Z");
    private static final UUID VIEWER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PROFILE = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID SESSION = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final Set<MusicianFeedItemType> SUPPORTED = Set.of(
            MusicianFeedItemType.SPONSORED, MusicianFeedItemType.COLLAB, MusicianFeedItemType.EVENT);

    private final MusicianFeedCollabCandidateProvider collabs = mock(MusicianFeedCollabCandidateProvider.class);
    private final MusicianFeedEventCandidateProvider events = mock(MusicianFeedEventCandidateProvider.class);
    private final DevelopmentMusicianFeedSponsorshipProvider provider =
            new DevelopmentMusicianFeedSponsorshipProvider(collabs, events);

    @Test
    void standaloneVarietyHasStableDistinctIdentitiesAndSupportedProductLinks() {
        var request = request(Set.of(MusicianFeedItemType.SPONSORED), 20);

        List<MusicianFeedCandidate> placements = validated(request);

        assertThat(placements).hasSize(6).doesNotHaveDuplicates();
        assertThat(placements).extracting(MusicianFeedCandidate::itemId).doesNotHaveDuplicates();
        assertThat(placements).extracting(candidate -> candidate.promotion().campaignId()).doesNotHaveDuplicates();
        assertThat(placements).extracting(candidate -> candidate.target().id()).doesNotHaveDuplicates();
        assertThat(placements).allSatisfy(candidate -> {
            assertThat(candidate.type()).isEqualTo(MusicianFeedItemType.SPONSORED);
            assertThat(candidate.reason().code()).isEqualTo(MusicianFeedReasonCode.SPONSORED);
            assertThat(candidate.promotion().disclosure()).isEqualTo("Sponsored");
            var payload = (MusicianFeedPayloads.SponsoredStandalone) candidate.payload();
            assertThat(payload.ctaUrl()).isIn("/collab", "/event-discovery");
            assertThat(payload.ctaLabel()).isEqualTo(candidate.promotion().ctaLabel());
            assertThat(payload.ctaUrl()).isEqualTo(candidate.promotion().ctaUrl());
            assertThat(payload.body()).doesNotContain("test", "bayrağı", "deterministik");
        });
        assertThat(validated(request)).isEqualTo(placements);
        verifyNoInteractions(collabs, events);
    }

    @Test
    void refreshCanRotateRankingWithoutManufacturingNewCampaignsOrCreatives() {
        var firstRequest = request(Set.of(MusicianFeedItemType.SPONSORED), 20);
        var secondRequest = new MusicianFeedCandidateRequest(VIEWER, PROFILE,
                UUID.fromString("00000000-0000-0000-0000-000000000009"), NOW.plusSeconds(60),
                NOW.plusSeconds(60), 20, firstRequest.supportedTypes(), firstRequest.personalization(),
                firstRequest.feedback(), firstRequest.delivery());

        var first = validated(firstRequest);
        var second = validated(secondRequest);

        assertThat(second).extracting(MusicianFeedCandidate::itemId)
                .containsExactlyElementsOf(first.stream().map(MusicianFeedCandidate::itemId).toList());
        assertThat(second).extracting(MusicianFeedCandidate::target)
                .containsExactlyElementsOf(first.stream().map(MusicianFeedCandidate::target).toList());
        assertThat(second).extracting(candidate -> candidate.promotion().campaignId())
                .containsExactlyElementsOf(first.stream().map(candidate -> candidate.promotion().campaignId()).toList());
        assertThat(second.stream().map(MusicianFeedCandidate::baseScore).toList())
                .isNotEqualTo(first.stream().map(MusicianFeedCandidate::baseScore).toList());
    }

    @Test
    void nativePromotionsRetainIdentityAuthorEngagementPayloadAndFeedback() {
        var collab = nativeCandidate(MusicianFeedItemType.COLLAB, "collab-one", false, false);
        var event = nativeCandidate(MusicianFeedItemType.EVENT, "event-one", false, false);
        stub(collabs, MusicianFeedItemType.COLLAB, List.of(collab));
        stub(events, MusicianFeedItemType.EVENT, List.of(event));
        var request = request(SUPPORTED, 20);

        var placements = validated(request);

        assertThat(placements).hasSize(8);
        assertNativePromotion(placements, collab, MusicianFeedReasonCode.FEATURED, "Featured", "/collab");
        assertNativePromotion(placements, event, MusicianFeedReasonCode.SPONSORED, "Sponsored", "/event-discovery");
        assertThat(validated(request)).extracting(candidate -> candidate.promotion().campaignId())
                .containsExactlyElementsOf(placements.stream().map(candidate -> candidate.promotion().campaignId()).toList());
    }

    @Test
    void nativeReadsAreBoundedAndKeepViewerVisibilityExpiryAndDeliveryContext() {
        stub(collabs, MusicianFeedItemType.COLLAB, List.of(
                nativeCandidate(MusicianFeedItemType.COLLAB, "collab-one", false, false),
                nativeCandidate(MusicianFeedItemType.COLLAB, "collab-two", false, false),
                nativeCandidate(MusicianFeedItemType.COLLAB, "collab-three", false, false)));
        stub(events, MusicianFeedItemType.EVENT, List.of(
                nativeCandidate(MusicianFeedItemType.EVENT, "event-one", false, false),
                nativeCandidate(MusicianFeedItemType.EVENT, "event-two", false, false),
                nativeCandidate(MusicianFeedItemType.EVENT, "event-three", false, false)));
        var request = request(SUPPORTED, 100);

        var placements = validated(request);

        assertThat(placements).hasSize(10);
        assertThat(placements.stream().filter(candidate -> candidate.type() == MusicianFeedItemType.COLLAB)).hasSize(2);
        assertThat(placements.stream().filter(candidate -> candidate.type() == MusicianFeedItemType.EVENT)).hasSize(2);
        var collabRequest = ArgumentCaptor.forClass(MusicianFeedCandidateRequest.class);
        var eventRequest = ArgumentCaptor.forClass(MusicianFeedCandidateRequest.class);
        verify(collabs).findCandidates(collabRequest.capture());
        verify(events).findCandidates(eventRequest.capture());
        for (var delegated : List.of(collabRequest.getValue(), eventRequest.getValue())) {
            assertThat(delegated.limit()).isEqualTo(6);
            assertThat(delegated.viewerUserId()).isEqualTo(request.viewerUserId());
            assertThat(delegated.musicianProfileId()).isEqualTo(request.musicianProfileId());
            assertThat(delegated.feedSessionId()).isEqualTo(request.feedSessionId());
            assertThat(delegated.anchor()).isEqualTo(request.anchor());
            assertThat(delegated.readAt()).isEqualTo(request.readAt());
            assertThat(delegated.personalization()).isSameAs(request.personalization());
            assertThat(delegated.feedback()).isSameAs(request.feedback());
            assertThat(delegated.delivery()).isSameAs(request.delivery());
        }
        assertThat(collabRequest.getValue().supportedTypes()).containsExactly(MusicianFeedItemType.COLLAB);
        assertThat(eventRequest.getValue().supportedTypes()).containsExactly(MusicianFeedItemType.EVENT);
    }

    @Test
    void unsupportedTypesOrEmptyCapacityNeverQueryNativeSources() {
        assertThat(provider.findPlacements(request(Set.of(MusicianFeedItemType.TRACK), 20))).isEmpty();
        assertThat(provider.findPlacements(request(SUPPORTED, 0))).isEmpty();
        assertThat(validated(request(Set.of(MusicianFeedItemType.SPONSORED), 1))).hasSize(1);
        verifyNoInteractions(collabs, events);
    }

    @Test
    void missingNativeContentDoesNotCreateFakeListingOrEventTargets() {
        stub(collabs, MusicianFeedItemType.COLLAB, List.of());
        stub(events, MusicianFeedItemType.EVENT, List.of());

        assertThat(validated(request(SUPPORTED, 20))).hasSize(6)
                .allSatisfy(candidate -> assertThat(candidate.type()).isEqualTo(MusicianFeedItemType.SPONSORED));
        assertThat(validated(request(Set.of(MusicianFeedItemType.COLLAB, MusicianFeedItemType.EVENT), 20))).isEmpty();
    }

    @Test
    void ownedSelfAuthoredAndDuplicateNativeTargetsAreNotPromotedTwice() {
        var eligible = nativeCandidate(MusicianFeedItemType.COLLAB, "eligible", false, false);
        stub(collabs, MusicianFeedItemType.COLLAB, List.of(
                nativeCandidate(MusicianFeedItemType.COLLAB, "owned", true, false),
                nativeCandidate(MusicianFeedItemType.COLLAB, "self", false, true),
                eligible, eligible));

        assertThat(validated(request(Set.of(MusicianFeedItemType.COLLAB), 20)))
                .singleElement().satisfies(candidate -> assertThat(candidate.itemId()).isEqualTo(eligible.itemId()));
        verifyNoInteractions(events);
    }

    @Test
    void hiddenOrAlreadyDeliveredContentDoesNotConsumeTheRemainingCandidateLimit() {
        var request = request(Set.of(MusicianFeedItemType.SPONSORED), 20);
        var original = validated(request);
        var delivered = original.get(0);
        var hidden = original.get(1);
        var deliveredTarget = original.get(2);
        var history = new MusicianFeedDeliverySnapshot(Set.of(delivered.itemId()),
                Set.of(targetKey(deliveredTarget)), Set.of(), Set.of(), 20, 1, false);
        var filteredRequest = new MusicianFeedCandidateRequest(VIEWER, PROFILE, SESSION, NOW, NOW, 2,
                request.supportedTypes(), request.personalization(),
                new MusicianFeedFeedbackSnapshot(Set.of(hidden.itemId()), Set.of(), Map.of()), history);

        assertThat(validated(filteredRequest)).hasSize(2)
                .extracting(MusicianFeedCandidate::itemId)
                .containsExactly(original.get(3).itemId(), original.get(4).itemId());
    }

    @Test
    void malformedNativeBatchIsIsolatedBeforeAnyOfItsCandidatesArePromoted() {
        var source = nativeCandidate(MusicianFeedItemType.COLLAB, "invalid", false, false);
        var malformed = new MusicianFeedCandidate(source.itemId(), source.type(), source.payloadVersion(),
                source.occurredAt(), source.reason(), source.author(), source.target(), source.engagement(),
                null, source.feedbackCapabilities(), new MusicianFeedPayloads.Collab(null),
                source.baseScore(), source.relevanceScore(), source.lane(), false);
        stub(collabs, MusicianFeedItemType.COLLAB, List.of(source, malformed));
        var event = nativeCandidate(MusicianFeedItemType.EVENT, "healthy-event", false, false);
        stub(events, MusicianFeedItemType.EVENT, List.of(event));

        assertThat(validated(request(SUPPORTED, 20))).hasSize(7)
                .extracting(MusicianFeedCandidate::type)
                .contains(MusicianFeedItemType.EVENT, MusicianFeedItemType.SPONSORED)
                .doesNotContain(MusicianFeedItemType.COLLAB);
    }

    @Test
    void failedNativeSourceDoesNotDiscardOtherNativeOrStandalonePlacements() {
        stub(collabs, MusicianFeedItemType.COLLAB, List.of());
        when(collabs.findCandidates(any())).thenThrow(new IllegalStateException("Native source unavailable"));
        stub(events, MusicianFeedItemType.EVENT,
                List.of(nativeCandidate(MusicianFeedItemType.EVENT, "healthy-event", false, false)));

        assertThat(validated(request(SUPPORTED, 20))).hasSize(7)
                .extracting(MusicianFeedCandidate::type)
                .contains(MusicianFeedItemType.EVENT, MusicianFeedItemType.SPONSORED)
                .doesNotContain(MusicianFeedItemType.COLLAB);
    }

    private List<MusicianFeedCandidate> validated(MusicianFeedCandidateRequest request) {
        return MusicianFeedCandidateContract.validateBatch(provider.providerId(), provider.supportedTypes(),
                request, provider.findPlacements(request), request.limit(), true);
    }

    private static void assertNativePromotion(List<MusicianFeedCandidate> placements,
                                              MusicianFeedCandidate source,
                                              MusicianFeedReasonCode reason,
                                              String disclosure, String ctaUrl) {
        assertThat(placements.stream().filter(candidate -> candidate.type() == source.type()))
                .singleElement().satisfies(candidate -> {
                    assertThat(candidate.itemId()).isEqualTo(source.itemId());
                    assertThat(candidate.occurredAt()).isEqualTo(source.occurredAt());
                    assertThat(candidate.target()).isSameAs(source.target());
                    assertThat(candidate.author()).isSameAs(source.author());
                    assertThat(candidate.payload()).isSameAs(source.payload());
                    assertThat(candidate.engagement()).isSameAs(source.engagement());
                    assertThat(candidate.feedbackCapabilities()).isEqualTo(source.feedbackCapabilities());
                    assertThat(candidate.reason().code()).isEqualTo(reason);
                    assertThat(candidate.promotion().disclosure()).isEqualTo(disclosure);
                    assertThat(candidate.promotion().ctaUrl()).isEqualTo(ctaUrl);
                });
    }

    private static void stub(MusicianFeedCandidateProvider source, MusicianFeedItemType type,
                             List<MusicianFeedCandidate> candidates) {
        when(source.providerId()).thenReturn("fixture-" + type.name().toLowerCase(Locale.ROOT) + "-source");
        when(source.supportedTypes()).thenReturn(Set.of(type));
        when(source.findCandidates(any())).thenReturn(candidates);
    }

    private static MusicianFeedCandidate nativeCandidate(MusicianFeedItemType type, String key,
                                                         boolean owned, boolean selfAuthored) {
        UUID targetId = UUID.nameUUIDFromBytes(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Object payload;
        if (type == MusicianFeedItemType.COLLAB) {
            var listing = mock(CollabListingResponse.class);
            when(listing.id()).thenReturn(targetId);
            payload = new MusicianFeedPayloads.Collab(listing);
        } else {
            var event = mock(EventResponseDto.class);
            when(event.id()).thenReturn(targetId);
            payload = new MusicianFeedPayloads.Event(event, null, null);
        }
        var author = new MusicianFeedItemResponse.Author(selfAuthored ? VIEWER : UUID.randomUUID(),
                UUID.randomUUID(), "MUSICIAN", "musician_username", "musician_username", null, true);
        var engagement = new MusicianFeedItemResponse.Engagement(type.name(), targetId, 4, 2, false, true, true);
        return new MusicianFeedCandidate(type.name() + ":" + targetId, type, 1, NOW.minusSeconds(60),
                new MusicianFeedItemResponse.Reason(MusicianFeedReasonCode.FOLLOWING_PUBLICATION, List.of(author), 0),
                author, new MusicianFeedItemResponse.Target(type.name(), targetId), engagement, null,
                List.of(MusicianFeedFeedbackAction.HIDE, MusicianFeedFeedbackAction.REPORT), payload,
                880_000, 100_000, MusicianFeedLane.FOLLOWING, owned);
    }

    private static String targetKey(MusicianFeedCandidate candidate) {
        return MusicianFeedDeliverySnapshot.targetKey(candidate.target().type(), candidate.target().id());
    }

    private static MusicianFeedCandidateRequest request(Set<MusicianFeedItemType> types, int limit) {
        return new MusicianFeedCandidateRequest(VIEWER, PROFILE, SESSION, NOW, NOW.plusSeconds(1), limit, types,
                new MusicianFeedPersonalizationSnapshot(UUID.randomUUID(), Set.of(UUID.randomUUID()), null),
                MusicianFeedFeedbackSnapshot.empty());
    }
}
