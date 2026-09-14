package com.berkayb.soundconnect.modules.feed.musician.core;

import com.berkayb.soundconnect.modules.feed.musician.api.*;
import com.berkayb.soundconnect.modules.feed.musician.announcement.MusicianFeedAnnouncementPlan;
import com.berkayb.soundconnect.modules.promotion.announcement.AnnouncementResponse;
import com.berkayb.soundconnect.modules.promotion.announcement.AnnouncementStatus;
import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileType;
import com.berkayb.soundconnect.modules.feed.musician.candidate.*;
import com.berkayb.soundconnect.modules.feed.musician.cursor.MusicianFeedCursorCodec;
import com.berkayb.soundconnect.modules.feed.musician.delivery.*;
import com.berkayb.soundconnect.modules.feed.musician.feedback.MusicianFeedFeedbackService;
import com.berkayb.soundconnect.modules.feed.musician.feedback.MusicianFeedFeedbackSnapshot;
import com.berkayb.soundconnect.modules.feed.musician.mixer.MusicianFeedMixer;
import com.berkayb.soundconnect.modules.feed.musician.moderation.MusicianFeedRestrictionGuard;
import com.berkayb.soundconnect.modules.feed.musician.personalization.MusicianFeedPersonalizationSnapshot;
import com.berkayb.soundconnect.modules.feed.musician.personalization.MusicianFeedPersonalizationSource;
import com.berkayb.soundconnect.modules.feed.musician.sponsor.MusicianFeedSponsorshipProvider;
import com.berkayb.soundconnect.modules.feed.musician.sponsor.MusicianFeedPromotionCadence;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class MusicianFeedServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-11T12:30:00Z");
    private final UUID viewer = UUID.fromString("fd30a85e-62bf-41fd-9588-03d6daf97a9f");
    private final UUID profile = UUID.randomUUID();
    private MusicianFeedViewerGuard guard;
    private MusicianFeedFeedbackService feedback;
    private MusicianFeedRestrictionGuard restrictions;
    private MusicianFeedPersonalizationSource personalization;
    private MusicianFeedProperties properties;
    private MusicianFeedDeliveryService deliveries;
    private ExecutorService executor;
    private io.micrometer.core.instrument.simple.SimpleMeterRegistry metricsRegistry;
    private final AtomicLong deliveredCount = new AtomicLong();
    private final Set<String> deliveredIds = ConcurrentHashMap.newKeySet();
    private final Set<String> deliveredTargets = ConcurrentHashMap.newKeySet();
    private final Set<String> promotedTargets = ConcurrentHashMap.newKeySet();
    private final Set<UUID> campaignIds = ConcurrentHashMap.newKeySet();
    private final AtomicLong promotionCount = new AtomicLong();
    private final AtomicLong organicCount = new AtomicLong();
    private final AtomicLong organicCountAtLastPromotion = new AtomicLong();
    private final AtomicLong normalCountAtLastAnnouncement = new AtomicLong();
    private final Set<UUID> announcementIds = ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.atomic.AtomicReference<MusicianFeedItemType> lastType =
            new java.util.concurrent.atomic.AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicReference<MusicianFeedLane> lastLane =
            new java.util.concurrent.atomic.AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicBoolean lastPromoted =
            new java.util.concurrent.atomic.AtomicBoolean();

    @BeforeEach
    void setUp() {
        guard = mock(MusicianFeedViewerGuard.class);
        feedback = mock(MusicianFeedFeedbackService.class);
        restrictions = mock(MusicianFeedRestrictionGuard.class);
        when(restrictions.filter(anyCollection())).thenAnswer(invocation -> List.copyOf(invocation.getArgument(0)));
        personalization = mock(MusicianFeedPersonalizationSource.class);
        properties = new MusicianFeedProperties();
        deliveries = mock(MusicianFeedDeliveryService.class);
        executor = Executors.newFixedThreadPool(4);
        metricsRegistry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        deliveredCount.set(0);
        deliveredIds.clear();
        deliveredTargets.clear();
        promotedTargets.clear();
        campaignIds.clear();
        promotionCount.set(0);
        organicCount.set(0);
        organicCountAtLastPromotion.set(0);
        normalCountAtLastAnnouncement.set(0);
        announcementIds.clear();
        lastType.set(null);
        lastLane.set(null);
        lastPromoted.set(false);
        properties.setCursorSecret("unit-test-musician-feed-secret-at-least-32-bytes");
        when(guard.requireMusicianProfile(viewer)).thenReturn(profile);
        when(feedback.snapshot(viewer)).thenReturn(MusicianFeedFeedbackSnapshot.empty());
        when(feedback.forCandidates(eq(viewer), any(), anyCollection(), anyCollection()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        when(personalization.load(viewer, profile)).thenReturn(MusicianFeedPersonalizationSnapshot.empty());
        when(deliveries.snapshot(eq(viewer), any(UUID.class), eq(NOW))).thenAnswer(ignored ->
                new MusicianFeedDeliverySnapshot(Set.copyOf(deliveredIds), Set.copyOf(deliveredTargets),
                        Set.copyOf(deliveredTargets), Set.copyOf(promotedTargets), Set.copyOf(campaignIds),
                        deliveredCount.get(), promotionCount.get(),
                        organicCountAtLastPromotion.get(), lastPromoted.get(), lastType.get(), lastLane.get(),
                        0, 0, Set.copyOf(announcementIds), organicCount.get(), normalCountAtLastAnnouncement.get()));
        when(deliveries.replay(eq(viewer), any(UUID.class), anyLong(), anyString(), anyInt(),
                anySet(), eq(NOW))).thenReturn(Optional.empty());
        when(deliveries.recordPage(eq(viewer), any(UUID.class), any(Instant.class), eq(1),
                eq(MusicianFeedService.ALGORITHM_VERSION), anyLong(), anyList(), anyList(), eq(NOW))).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked") List<MusicianFeedItemResponse> items = invocation.getArgument(6);
            @SuppressWarnings("unchecked") List<MusicianFeedLane> lanes = invocation.getArgument(7);
            long start = invocation.getArgument(5);
            List<MusicianFeedItemResponse> result = new ArrayList<>();
            for (int index = 0; index < items.size(); index++) {
                deliveredIds.add(items.get(index).id());
                String targetKey = MusicianFeedDeliverySnapshot.targetKey(
                        items.get(index).target().type(), items.get(index).target().id());
                if (items.get(index).type() != MusicianFeedItemType.ACTIVITY_COMMENT) {
                    deliveredTargets.add(targetKey);
                }
                if (items.get(index).promotion() != null) {
                    promotedTargets.add(targetKey);
                    campaignIds.add(items.get(index).promotion().campaignId());
                    promotionCount.incrementAndGet();
                    organicCountAtLastPromotion.set(organicCount.get());
                } else if (items.get(index).type() == MusicianFeedItemType.ANNOUNCEMENT) {
                    announcementIds.add(items.get(index).target().id());
                    normalCountAtLastAnnouncement.set(organicCount.get());
                } else {
                    organicCount.incrementAndGet();
                }
                lastType.set(items.get(index).type());
                lastLane.set(lanes.get(index));
                lastPromoted.set(items.get(index).promotion() != null);
                result.add(items.get(index).withDelivery(start + index, "signed-token"));
            }
            deliveredCount.addAndGet(items.size());
            return result;
        });
        when(deliveries.recordPageAndReplay(eq(viewer), any(UUID.class), any(Instant.class), eq(1),
                eq(MusicianFeedService.ALGORITHM_VERSION), anyLong(), anyString(), anyInt(), anySet(), anyList(),
                anyList(), nullable(String.class), anyBoolean(), eq(NOW))).thenAnswer(invocation -> {
            UUID session = invocation.getArgument(1);
            Instant anchor = invocation.getArgument(2);
            long start = invocation.getArgument(5);
            @SuppressWarnings("unchecked") List<MusicianFeedItemResponse> items = invocation.getArgument(9);
            @SuppressWarnings("unchecked") List<MusicianFeedLane> lanes = invocation.getArgument(10);
            String nextCursor = invocation.getArgument(11);
            boolean hasMore = invocation.getArgument(12);
            List<MusicianFeedItemResponse> delivered = deliveries.recordPage(viewer, session, anchor, 1,
                    MusicianFeedService.ALGORITHM_VERSION, start, items, lanes, NOW);
            return new MusicianFeedPageResponse(1, MusicianFeedService.ALGORITHM_VERSION, session, NOW,
                    delivered, nextCursor, hasMore);
        });
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void globalRestrictionsCoverOptionalProvidersAndPromotionsBeforeMixing() {
        var blocked = candidate("TRACK:globally-blocked", 1_000_000);
        var kept = candidate("TRACK:globally-kept", 900_000);
        var promoted = promotedCollab("COLLAB:blocked-promotion", UUID.randomUUID());
        var repository = mock(com.berkayb.soundconnect.modules.feed.musician.moderation.MusicianFeedRestrictionRepository.class);
        Set<String> active = Set.of("TARGET:MEDIA:" + blocked.target().id(), "ITEM:" + promoted.itemId());
        when(repository.activeScopes(anyCollection())).thenAnswer(invocation -> {
            Collection<String> requested = invocation.getArgument(0);
            return requested.stream().filter(active::contains).collect(java.util.stream.Collectors.toSet());
        });
        restrictions = new MusicianFeedRestrictionGuard(repository,
                new com.berkayb.soundconnect.modules.feed.musician.moderation.MusicianFeedModerationScopeResolver(new ObjectMapper()));

        var page = service(List.of(provider("new-extension", request -> List.of(blocked, kept))),
                List.of(sponsorship("campaign", Set.of(MusicianFeedItemType.COLLAB), request -> List.of(promoted))), executor)
                .get(viewer, 20, null, List.of("TRACK", "COLLAB"));

        assertThat(page.items()).extracting(MusicianFeedItemResponse::id).containsExactly(kept.itemId());
        verify(repository, times(2)).activeScopes(anyCollection());
    }

    @Test
    void centralFeedbackSuppressesCompletionAndANewOptionalProviderAfterCollection() {
        var ranking = new MusicianFeedFeedbackSnapshot(Set.of(), Set.of(),
                Map.of(MusicianFeedItemType.TRACK, 2), "ranking-only");
        when(feedback.snapshot(viewer)).thenReturn(ranking);
        var completion = new MusicianFeedPayloads.Completion(0, 1, List.of(
                new MusicianFeedPayloads.CompletionTask("bio", "Bio", "Add your bio", "Edit",
                        "/profile/edit", 1, false)));
        when(personalization.load(viewer, profile)).thenReturn(
                new MusicianFeedPersonalizationSnapshot(null, Set.of(), completion));
        var muted = candidate("TRACK:optional-muted", 1_000_000);
        var kept = candidate("TRACK:optional-kept", 900_000);
        String completionId = "PROFILE_COMPLETION:" + profile;
        var suppressed = new MusicianFeedFeedbackSnapshot(Set.of(completionId), Set.of(
                MusicianFeedFeedbackSnapshot.authorKey(muted.author().profileType(), muted.author().profileId())),
                ranking.showLessCounts(), ranking.rankingContextVersion());
        when(feedback.forCandidates(eq(viewer), eq(ranking), anyCollection(), anyCollection()))
                .thenAnswer(invocation -> {
                    Collection<MusicianFeedCandidate> candidates = invocation.getArgument(2);
                    assertThat(candidates).extracting(MusicianFeedCandidate::itemId)
                            .containsExactlyInAnyOrder(completionId, muted.itemId(), kept.itemId());
                    return suppressed;
                });
        var optional = provider("new-optional-extension", request -> {
            assertThat(request.feedback()).isEqualTo(ranking);
            return List.of(muted, kept);
        });

        var page = service(List.of(optional,
                new com.berkayb.soundconnect.modules.feed.musician.provider.MusicianFeedCompletionCandidateProvider()))
                .get(viewer, 10, null, List.of("TRACK", "PROFILE_COMPLETION"));

        assertThat(page.items()).extracting(MusicianFeedItemResponse::id).containsExactly(kept.itemId());
        verify(feedback).forCandidates(eq(viewer), eq(ranking), anyCollection(), eq(List.of()));
    }

    @Test
    void centralFeedbackAlsoChecksPromotionCandidates() {
        List<MusicianFeedCandidate> organic = java.util.stream.IntStream.range(0, 15)
                .mapToObj(index -> candidate("TRACK:organic-" + index, 1_000_000 - index)).toList();
        var promotion = promotedCollab("COLLAB:promotion", UUID.randomUUID());
        when(feedback.forCandidates(eq(viewer), any(), eq(organic), eq(List.of(promotion))))
                .thenReturn(new MusicianFeedFeedbackSnapshot(Set.of(promotion.itemId()), Set.of(), Map.of()));

        var page = service(List.of(provider("tracks", request -> organic)), List.of(
                sponsorship("campaign", Set.of(MusicianFeedItemType.COLLAB), request -> List.of(promotion))), executor)
                .get(viewer, 20, null, List.of("TRACK", "COLLAB"));

        assertThat(page.items()).hasSize(15).allMatch(item -> item.promotion() == null);
        verify(feedback).forCandidates(eq(viewer), any(), eq(organic), eq(List.of(promotion)));
    }

    @Test
    void optionalProviderFailureDoesNotDropHealthySources() {
        MusicianFeedCandidateProvider failed = provider("a-failed", request -> {
            throw new IllegalStateException("optional source unavailable");
        });
        MusicianFeedCandidate kept = candidate("TRACK:kept", 900_000);
        MusicianFeedCandidateProvider healthy = provider("z-healthy", request -> List.of(kept));

        MusicianFeedPageResponse page = service(List.of(failed, healthy)).get(
                viewer, 10, null, List.of("TRACK"));

        assertThat(page.items()).extracting(MusicianFeedItemResponse::id).containsExactly("TRACK:kept");
        assertThat(page.schemaVersion()).isEqualTo(1);
        assertThat(page.algorithmVersion()).isEqualTo(MusicianFeedService.ALGORITHM_VERSION);
    }

    @Test
    void malformedOptionalProviderBatchIsDroppedWithoutLeakingItsValidPrefix() {
        MusicianFeedCandidate prefix = candidate("TRACK:poison-prefix", 1_000_000);
        MusicianFeedCandidate malformed = new MusicianFeedCandidate("TRACK:malformed",
                MusicianFeedItemType.TRACK, 1, NOW.minusSeconds(60), null, prefix.author(),
                prefix.target(), prefix.engagement(), null, prefix.feedbackCapabilities(),
                prefix.payload(), 990_000, 0, MusicianFeedLane.FOLLOWING, false);
        MusicianFeedCandidate kept = candidate("TRACK:healthy", 900_000);

        MusicianFeedPageResponse page = service(List.of(
                provider("malformed-optional", request -> List.of(prefix, malformed)),
                provider("healthy", request -> List.of(kept))))
                .get(viewer, 10, null, List.of("TRACK"));

        assertThat(page.items()).extracting(MusicianFeedItemResponse::id)
                .containsExactly("TRACK:healthy");
    }

    @Test
    void malformedRequiredProviderFailsClosed() {
        MusicianFeedCandidate base = candidate("TRACK:required-malformed", 1_000_000);
        MusicianFeedCandidate malformed = new MusicianFeedCandidate(base.itemId(), base.type(),
                base.payloadVersion(), base.occurredAt(), null, base.author(), base.target(),
                base.engagement(), null, base.feedbackCapabilities(), base.payload(), base.baseScore(),
                base.relevanceScore(), base.lane(), false);
        MusicianFeedCandidateProvider required = new MusicianFeedCandidateProvider() {
            @Override public String providerId() { return "required-malformed"; }
            @Override public Set<MusicianFeedItemType> supportedTypes() {
                return Set.of(MusicianFeedItemType.TRACK);
            }
            @Override public boolean optional() { return false; }
            @Override public List<MusicianFeedCandidate> findCandidates(MusicianFeedCandidateRequest request) {
                return List.of(malformed);
            }
        };

        assertThatThrownBy(() -> service(List.of(required)).get(viewer, 10, null, List.of("TRACK")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("required-malformed");
    }

    @Test
    void unsafeOptionalPromotionIsDroppedAtTheProviderBoundary() {
        MusicianFeedCandidate healthy = candidate("TRACK:healthy", 900_000);
        UUID creativeId = UUID.randomUUID();
        var unsafe = new MusicianFeedCandidate("SPONSORED:" + creativeId,
                MusicianFeedItemType.SPONSORED, 1, NOW.minusSeconds(1),
                new MusicianFeedItemResponse.Reason(MusicianFeedReasonCode.SPONSORED, List.of(), 0),
                null, new MusicianFeedItemResponse.Target("STANDALONE", creativeId), null,
                new MusicianFeedItemResponse.Promotion(UUID.randomUUID(), "Sponsored", "Aç",
                        "javascript:alert(1)"),
                List.of(MusicianFeedFeedbackAction.HIDE),
                new MusicianFeedPayloads.SponsoredStandalone("Başlık", "Gövde", null,
                        "Aç", "javascript:alert(1)"),
                2_000_000, 0, MusicianFeedLane.SYSTEM, false);

        MusicianFeedPageResponse page = service(List.of(provider("healthy", request -> List.of(healthy))),
                List.of(sponsorship("unsafe", Set.of(MusicianFeedItemType.SPONSORED),
                        request -> List.of(unsafe))), executor)
                .get(viewer, 10, null, List.of("TRACK", "SPONSORED"));

        assertThat(page.items()).extracting(MusicianFeedItemResponse::id)
                .containsExactly("TRACK:healthy");
    }

    @Test
    void slowFirstOptionalProviderDoesNotDiscardAnAlreadyCompletedHealthyProvider() {
        properties.setProviderDeadline(Duration.ofMillis(80));
        MusicianFeedCandidateProvider slow = provider("a-slow", request -> {
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return List.of();
        });
        MusicianFeedCandidate kept = candidate("TRACK:healthy-after-slow", 900_000);
        MusicianFeedCandidateProvider healthy = provider("z-healthy", request -> List.of(kept));

        MusicianFeedPageResponse page = service(List.of(slow, healthy)).get(
                viewer, 10, null, List.of("TRACK"));

        assertThat(page.items()).extracting(MusicianFeedItemResponse::id)
                .containsExactly("TRACK:healthy-after-slow");
    }

    @Test
    void cursorUsesFixedAnchorAndKeysetWithoutDuplicates() {
        MusicianFeedCandidate first = candidate("TRACK:first", 1_000_000);
        MusicianFeedCandidate second = candidate("TRACK:second", 900_000);
        MusicianFeedService service = service(List.of(provider("tracks", request -> List.of(first, second))));

        MusicianFeedPageResponse pageOne = service.get(viewer, 1, null, List.of("TRACK"));
        MusicianFeedPageResponse pageTwo = service.get(viewer, 1, pageOne.nextCursor(), List.of("TRACK"));

        assertThat(pageOne.items()).extracting(MusicianFeedItemResponse::id).containsExactly("TRACK:first");
        assertThat(pageTwo.items()).extracting(MusicianFeedItemResponse::id).containsExactly("TRACK:second");
        assertThat(pageTwo.feedSessionId()).isEqualTo(pageOne.feedSessionId());
        assertThat(pageTwo.nextCursor()).isNull();
        assertThat(pageTwo.hasMore()).isFalse();
    }

    @Test
    void exactContinuationRetryReturnsTheCommittedPageWithoutCollectingAgain() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        MusicianFeedCandidate first = candidate("TRACK:first", 1_000_000);
        MusicianFeedCandidate second = candidate("TRACK:second", 900_000);
        MusicianFeedCandidate third = candidate("TRACK:third", 800_000);
        MusicianFeedService service = service(List.of(provider("tracks", request -> {
            calls.incrementAndGet();
            return List.of(first, second, third);
        })));

        MusicianFeedPageResponse firstPage = service.get(viewer, 1, null, List.of("TRACK"));
        String continuation = firstPage.nextCursor();
        MusicianFeedPageResponse committed = service.get(viewer, 1, continuation, List.of("TRACK"));
        int callsAfterCommit = calls.get();
        when(deliveries.replay(eq(viewer), eq(committed.feedSessionId()), eq(1L), anyString(), eq(1),
                eq(Set.of(MusicianFeedItemType.TRACK)), eq(NOW))).thenReturn(Optional.of(committed));

        MusicianFeedPageResponse replayed = service.get(viewer, 1, continuation, List.of("TRACK"));

        assertThat(replayed).isEqualTo(committed);
        assertThat(calls.get()).isEqualTo(callsAfterCommit);
    }

    @Test
    void exactContinuationRechecksReplayWhenConcurrentCommitAdvancesLedgerAfterInitialMiss() {
        var providerCalls = new java.util.concurrent.atomic.AtomicInteger();
        MusicianFeedCandidate first = candidate("TRACK:first", 1_000_000);
        MusicianFeedCandidate second = candidate("TRACK:second", 900_000);
        MusicianFeedCandidate third = candidate("TRACK:third", 800_000);
        MusicianFeedService service = service(List.of(provider("tracks", request -> {
            providerCalls.incrementAndGet();
            return List.of(first, second, third);
        })));
        MusicianFeedPageResponse firstPage = service.get(viewer, 1, null, List.of("TRACK"));
        MusicianFeedPageResponse concurrentlyCommitted = new MusicianFeedPageResponse(
                1, MusicianFeedService.ALGORITHM_VERSION, firstPage.feedSessionId(), NOW,
                List.of(second.toResponse().withDelivery(1, "concurrent-signed-token")),
                null, false);
        var replayLookups = new java.util.concurrent.atomic.AtomicInteger();
        when(deliveries.replay(eq(viewer), eq(firstPage.feedSessionId()), eq(1L), anyString(), eq(1),
                eq(Set.of(MusicianFeedItemType.TRACK)), eq(NOW))).thenAnswer(invocation -> {
            if (replayLookups.incrementAndGet() == 1) {
                // Deterministic interleaving: the competing request commits after this lookup's
                // read point, so this call observes a miss while the following snapshot sees it.
                deliveredCount.incrementAndGet();
                return Optional.empty();
            }
            return Optional.of(concurrentlyCommitted);
        });

        MusicianFeedPageResponse replayed = service.get(
                viewer, 1, firstPage.nextCursor(), List.of("TRACK"));

        assertThat(replayed).isEqualTo(concurrentlyCommitted);
        assertThat(replayLookups).hasValue(2);
        assertThat(providerCalls).hasValue(1);
        var fingerprint = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(deliveries, times(2)).replay(eq(viewer), eq(firstPage.feedSessionId()), eq(1L),
                fingerprint.capture(), eq(1), eq(Set.of(MusicianFeedItemType.TRACK)), eq(NOW));
        assertThat(fingerprint.getAllValues()).containsOnly(fingerprint.getValue());
    }

    @Test
    void ledgerAdvanceWithoutAnExactReplayReturnsTheStableCursorRefreshError() {
        MusicianFeedService service = service(List.of(provider("tracks", request -> List.of(
                candidate("TRACK:first", 1_000_000), candidate("TRACK:second", 900_000)))));
        MusicianFeedPageResponse first = service.get(viewer, 1, null, List.of("TRACK"));
        deliveredCount.incrementAndGet();

        assertThatThrownBy(() -> service.get(viewer, 1, first.nextCursor(), List.of("TRACK")))
                .isInstanceOfSatisfying(SoundConnectException.class, failure ->
                        assertThat(failure.getErrorType())
                                .isEqualTo(ErrorType.MUSICIAN_FEED_CURSOR_INVALID));
    }

    @Test
    void rendererNegotiationIsMandatoryExactAndBoundToTheCursor() {
        MusicianFeedService service = service(List.of(provider("tracks",
                request -> List.of(candidate("TRACK:first", 1_000_000), candidate("TRACK:second", 900_000)))));

        assertThatThrownBy(() -> service.get(viewer, 10, null, null))
                .isInstanceOf(SoundConnectException.class);
        assertThatThrownBy(() -> service.get(viewer, 10, null, List.of("UNKNOWN", "FUTURE_TYPE")))
                .isInstanceOf(SoundConnectException.class);
        assertThat(service.parseSupportedTypes(List.of("TRACK,UNKNOWN", "FUTURE_TYPE")))
                .containsExactly(MusicianFeedItemType.TRACK);
        MusicianFeedPageResponse first = service.get(viewer, 1, null, List.of("TRACK"));
        assertThatThrownBy(() -> service.get(viewer, 1, first.nextCursor(), List.of("TRACK", "PROFILE")))
                .isInstanceOf(SoundConnectException.class);
    }

    @Test
    void optionalProviderCannotExceedItsBoundedContract() {
        MusicianFeedCandidateProvider oversized = provider("oversized", request -> {
            List<MusicianFeedCandidate> result = new ArrayList<>();
            for (int index = 0; index <= request.limit(); index++) {
                result.add(candidate("TRACK:" + index, 1_000_000 - index));
            }
            return result;
        });

        MusicianFeedPageResponse page = service(List.of(oversized)).get(viewer, 1, null, List.of("TRACK"));

        assertThat(page.items()).isEmpty();
    }

    @Test
    void ledgerContinuationExhaustsMoreThanOneProviderWindowWithoutGapsOrDuplicates() {
        properties.setProviderLimit(8);
        List<MusicianFeedCandidate> source = java.util.stream.IntStream.range(0, 25)
                .mapToObj(index -> candidate("TRACK:" + index, 1_000_000 - index * 10_000L)).toList();
        MusicianFeedService service = service(List.of(provider("tracks", request -> source.stream()
                .filter(value -> !request.delivery().itemIds().contains(value.itemId()))
                .limit(request.limit()).toList())));

        List<String> received = new ArrayList<>();
        String cursor = null;
        do {
            MusicianFeedPageResponse page = service.get(viewer, 2, cursor, List.of("TRACK"));
            received.addAll(page.items().stream().map(MusicianFeedItemResponse::id).toList());
            cursor = page.nextCursor();
            if (!page.hasMore()) break;
        } while (received.size() < 100);

        assertThat(received).containsExactlyElementsOf(source.stream()
                .map(MusicianFeedCandidate::itemId).toList());
        assertThat(new HashSet<>(received)).hasSize(25);
    }

    @Test
    void personalizationMutationInvalidatesAnOldSessionCursor() {
        UUID firstCity = UUID.randomUUID();
        UUID secondCity = UUID.randomUUID();
        when(personalization.load(viewer, profile))
                .thenReturn(new MusicianFeedPersonalizationSnapshot(firstCity, Set.of(), null))
                .thenReturn(new MusicianFeedPersonalizationSnapshot(secondCity, Set.of(), null));
        MusicianFeedService service = service(List.of(provider("tracks", request -> List.of(
                candidate("TRACK:first", 1_000_000), candidate("TRACK:second", 900_000)))));

        MusicianFeedPageResponse first = service.get(viewer, 1, null, List.of("TRACK"));

        assertThatThrownBy(() -> service.get(viewer, 1, first.nextCursor(), List.of("TRACK")))
                .isInstanceOf(SoundConnectException.class);
    }

    @Test
    void nativePromotionDoesNotRequireTheStandaloneSponsoredRenderer() {
        int firstGap = MusicianFeedPromotionCadence.organicGap(viewer, NOW, 0);
        List<MusicianFeedCandidate> organic = java.util.stream.IntStream.range(0, firstGap + 1)
                .mapToObj(index -> candidate("TRACK:" + index, 1_000_000 - index)).toList();
        UUID target = UUID.randomUUID();
        var promoted = promotedCollab("COLLAB:paid", target);
        MusicianFeedSponsorshipProvider sponsor = sponsorship("native", Set.of(MusicianFeedItemType.COLLAB),
                request -> List.of(promoted));

        MusicianFeedPageResponse page = service(List.of(provider("tracks", request -> organic)),
                List.of(sponsor), executor).get(viewer, firstGap + 1, null, List.of("TRACK", "COLLAB"));

        assertThat(page.items()).anyMatch(value -> value.type() == MusicianFeedItemType.COLLAB
                && value.promotion() != null);
    }

    @Test
    void variableCadenceSurvivesPaginationAndReplayAndAllowsDistinctTargetsFromOneCampaign() {
        properties.setProviderLimit(8);
        UUID sharedCampaign = UUID.fromString("6f4e66c1-2a14-4fc6-9895-5a8db7a3a4aa");
        List<MusicianFeedCandidate> organic = java.util.stream.IntStream.range(0, 70)
                .mapToObj(index -> candidate("TRACK:paged:" + index, 1_000_000 - index * 10_000L))
                .toList();
        List<MusicianFeedCandidate> sponsors = java.util.stream.IntStream.range(0, 5)
                .mapToObj(index -> promotedCollab("COLLAB:distinct-paid:" + index,
                        UUID.randomUUID(), sharedCampaign)).toList();
        var providerCalls = new java.util.concurrent.atomic.AtomicInteger();
        MusicianFeedService service = service(List.of(provider("paged-tracks", request -> {
            providerCalls.incrementAndGet();
            return organic.stream().filter(item -> !request.delivery().itemIds().contains(item.itemId()))
                    .limit(request.limit()).toList();
        })), List.of(sponsorship("one-campaign", Set.of(MusicianFeedItemType.COLLAB), request -> {
            providerCalls.incrementAndGet();
            return sponsors;
        })), executor);
        List<String> supportedNames = List.of("TRACK", "COLLAB");
        Set<MusicianFeedItemType> supportedTypes = Set.of(MusicianFeedItemType.TRACK,
                MusicianFeedItemType.COLLAB);
        List<MusicianFeedItemResponse> received = new ArrayList<>();
        String cursor = null;
        String sponsorRequestCursor = null;
        int sponsorRequestSize = 0;
        long sponsorRequestOffset = 0;
        MusicianFeedPageResponse sponsorPage = null;
        int[] pageSizes = {2, 5, 3};
        for (int pageIndex = 0; pageIndex < 60; pageIndex++) {
            int pageSize = pageSizes[pageIndex % pageSizes.length];
            long offset = received.size();
            MusicianFeedPageResponse page = service.get(viewer, pageSize, cursor, supportedNames);
            assertThat(page.items()).isNotEmpty();
            if (sponsorPage == null && cursor != null
                    && page.items().stream().anyMatch(item -> item.promotion() != null)) {
                sponsorRequestCursor = cursor;
                sponsorRequestSize = pageSize;
                sponsorRequestOffset = offset;
                sponsorPage = page;
            }
            received.addAll(page.items());
            cursor = page.nextCursor();
            if (!page.hasMore()) break;
        }

        assertThat(cursor).isNull();
        assertThat(received).hasSize(75);
        assertThat(received).extracting(MusicianFeedItemResponse::id).doesNotHaveDuplicates();
        List<Integer> promotionPositions = java.util.stream.IntStream.range(0, received.size())
                .filter(index -> received.get(index).promotion() != null).boxed().toList();
        List<Integer> expectedPositions = new ArrayList<>();
        int nextPosition = 0;
        for (int ordinal = 0; ordinal < sponsors.size(); ordinal++) {
            nextPosition += MusicianFeedPromotionCadence.organicGap(viewer, NOW, ordinal);
            expectedPositions.add(nextPosition++);
        }
        assertThat(promotionPositions).containsExactlyElementsOf(expectedPositions).hasSize(5);
        assertThat(received.stream().filter(item -> item.promotion() != null))
                .allMatch(item -> item.promotion().campaignId().equals(sharedCampaign));

        assertThat(sponsorPage).isNotNull();
        when(deliveries.replay(eq(viewer), eq(sponsorPage.feedSessionId()), eq(sponsorRequestOffset),
                anyString(), eq(sponsorRequestSize), eq(supportedTypes), eq(NOW)))
                .thenReturn(Optional.of(sponsorPage));
        int callsBeforeReplay = providerCalls.get();
        long deliveriesBeforeReplay = deliveredCount.get();
        assertThat(service.get(viewer, sponsorRequestSize, sponsorRequestCursor, supportedNames))
                .isEqualTo(sponsorPage);
        assertThat(providerCalls.get()).isEqualTo(callsBeforeReplay);
        assertThat(deliveredCount.get()).isEqualTo(deliveriesBeforeReplay);
        assertThat(promotionCount.get()).isEqualTo(5);
    }

    @Test
    void optionalSponsorSharesTheGlobalDeadlineAndFailsClosed() {
        properties.setProviderDeadline(Duration.ofMillis(100));
        MusicianFeedSponsorshipProvider slow = sponsorship("slow", Set.of(MusicianFeedItemType.SPONSORED),
                request -> {
                    try { Thread.sleep(2_000); } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return List.of();
                });
        long started = System.nanoTime();

        MusicianFeedPageResponse page = service(List.of(provider("tracks",
                request -> List.of(candidate("TRACK:kept", 1_000_000)))), List.of(slow), executor)
                .get(viewer, 10, null, List.of("TRACK", "SPONSORED"));

        assertThat(page.items()).extracting(MusicianFeedItemResponse::id).containsExactly("TRACK:kept");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
    }

    @Test
    void sessionCapacityClampsTheLastPageAndTerminatesCleanly() {
        properties.setDefaultPageSize(2);
        properties.setMaxPageSize(2);
        properties.setMaxSessionDeliveries(3);
        List<MusicianFeedCandidate> values = List.of(candidate("TRACK:1", 1_000_000),
                candidate("TRACK:2", 900_000), candidate("TRACK:3", 800_000),
                candidate("TRACK:4", 700_000));
        MusicianFeedService service = service(List.of(provider("tracks", request -> values.stream()
                .filter(value -> !request.delivery().itemIds().contains(value.itemId())).toList())));

        MusicianFeedPageResponse first = service.get(viewer, 2, null, List.of("TRACK"));
        MusicianFeedPageResponse last = service.get(viewer, 2, first.nextCursor(), List.of("TRACK"));

        assertThat(last.items()).hasSize(1);
        assertThat(last.hasMore()).isFalse();
        assertThat(last.nextCursor()).isNull();
    }

    @Test
    void admissionTimeoutCancelsEarlierQueuedProvidersAndDoesNotPersistAnEmptyPage() throws Exception {
        properties.setProviderDeadline(Duration.ofMillis(80));
        MusicianFeedProviderExecutor bounded = new MusicianFeedProviderExecutor(1, 1, Executors.defaultThreadFactory());
        CountDownLatch occupied = new CountDownLatch(1), release = new CountDownLatch(1);
        bounded.submit(() -> {
            occupied.countDown();
            await(release);
        });
        assertThat(occupied.await(1, TimeUnit.SECONDS)).isTrue();
        AtomicLong calls = new AtomicLong();
        try {
            MusicianFeedService service = service(List.of(
                    provider("a-queued", request -> { calls.incrementAndGet(); return List.of(); }),
                    provider("z-no-capacity", request -> { calls.incrementAndGet(); return List.of(); })),
                    List.of(), bounded);
            assertThatThrownBy(() -> service.get(viewer, 10, null, List.of("TRACK")))
                    .isInstanceOfSatisfying(SoundConnectException.class, failure ->
                            assertThat(failure.getErrorType()).isEqualTo(ErrorType.MUSICIAN_FEED_CAPACITY_UNAVAILABLE));
            assertThat(calls.get()).isZero();
            assertThat(bounded.getQueue()).isEmpty();
            assertThat(bounded.getActiveCount()).isEqualTo(1);
            assertNoPagePersisted();
        } finally {
            release.countDown();
            bounded.shutdownNow();
            assertThat(bounded.awaitTermination(1, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void announcementPlanIsSignedBeforeItsFirstExposureAndStaysFrozenAcrossSingleCardPages() {
        var plan = new MusicianFeedAnnouncementPlan(List.of(
                new MusicianFeedAnnouncementPlan.Entry(new UUID(70, 1), 1),
                new MusicianFeedAnnouncementPlan.Entry(new UUID(70, 2), 4),
                new MusicianFeedAnnouncementPlan.Entry(new UUID(70, 3), 4)));
        List<MusicianFeedCandidate> announcements = plan.entries().stream().map(entry -> {
            var payload = new AnnouncementResponse(entry.id(), 0, "Duyuru", "Platform açıklaması", Set.of(ProfileType.MUSICIAN),
                    AnnouncementStatus.PUBLISHED, null, null, NOW.minusSeconds(1), NOW, NOW, null,
                    new AnnouncementResponse.Engagement(0, 0, false), false);
            return new MusicianFeedCandidate("ANNOUNCEMENT:" + entry.id(), MusicianFeedItemType.ANNOUNCEMENT, 1, NOW.minusSeconds(1),
                    new MusicianFeedItemResponse.Reason(MusicianFeedReasonCode.PLATFORM_ANNOUNCEMENT, List.of(), 0), null,
                    new MusicianFeedItemResponse.Target("ANNOUNCEMENT", entry.id()), null, null,
                    List.of(MusicianFeedFeedbackAction.HIDE), payload, 0, 0, MusicianFeedLane.SYSTEM, false, entry);
        }).toList();
        AtomicLong initialSelections = new AtomicLong();
        var announcementProvider = new MusicianFeedCandidateProvider() {
            @Override public String providerId() { return "announcements"; }
            @Override public Set<MusicianFeedItemType> supportedTypes() { return Set.of(MusicianFeedItemType.ANNOUNCEMENT); }
            @Override public List<MusicianFeedCandidate> findCandidates(MusicianFeedCandidateRequest request) {
                if (request.announcementPlan() == null) initialSelections.incrementAndGet();
                else assertThat(request.announcementPlan()).isEqualTo(plan);
                return announcements.stream().filter(value -> !request.delivery().itemIds().contains(value.itemId())).toList();
            }
        };
        var tracks = java.util.stream.IntStream.range(0, 12).mapToObj(id -> candidate("TRACK:plan:" + id, 1_000_000 - id)).toList();
        var service = service(List.of(announcementProvider, provider("tracks", request -> tracks.stream()
                .filter(value -> !request.delivery().itemIds().contains(value.itemId())).limit(request.limit()).toList())));
        var first = service.get(viewer, 1, null, List.of("TRACK", "ANNOUNCEMENT"));
        assertThat(first.items()).singleElement().satisfies(value -> assertThat(value.type()).isEqualTo(MusicianFeedItemType.TRACK));
        var decoded = new MusicianFeedCursorCodec(new ObjectMapper(), properties).decodeForReplay(first.nextCursor(), viewer,
                Set.of(MusicianFeedItemType.TRACK, MusicianFeedItemType.ANNOUNCEMENT), NOW);
        assertThat(decoded.announcementPlan()).isEqualTo(plan);
        List<MusicianFeedItemResponse> shown = new ArrayList<>(first.items());
        var page = first;
        for (int index = 0; page.hasMore() && index < 30; index++) {
            page = service.get(viewer, 1, page.nextCursor(), List.of("TRACK", "ANNOUNCEMENT"));
            shown.addAll(page.items());
        }
        assertThat(page.hasMore()).isFalse();
        assertThat(initialSelections.get()).isEqualTo(1);
        assertThat(shown).hasSize(15);
        assertThat(shown.stream().filter(value -> value.type() == MusicianFeedItemType.ANNOUNCEMENT))
                .extracting(value -> value.target().id()).containsExactlyElementsOf(plan.entries().stream().map(MusicianFeedAnnouncementPlan.Entry::id).toList());
        assertThat(organicCount.get()).isEqualTo(12);
    }

    @Test
    void acceptedButNeverStartedProviderExpiresAsUnavailableWithoutEndingTheFeed() throws Exception {
        properties.setProviderDeadline(Duration.ofMillis(80));
        MusicianFeedProviderExecutor bounded = new MusicianFeedProviderExecutor(1, 1, Executors.defaultThreadFactory());
        CountDownLatch occupied = new CountDownLatch(1), release = new CountDownLatch(1);
        bounded.submit(() -> {
            occupied.countDown();
            await(release);
        });
        assertThat(occupied.await(1, TimeUnit.SECONDS)).isTrue();
        AtomicLong calls = new AtomicLong();
        try {
            MusicianFeedService service = service(List.of(provider("queued", request -> {
                calls.incrementAndGet();
                return List.of();
            })), List.of(), bounded);
            assertThatThrownBy(() -> service.get(viewer, 10, null, List.of("TRACK")))
                    .isInstanceOfSatisfying(SoundConnectException.class, failure ->
                            assertThat(failure.getErrorType()).isEqualTo(ErrorType.MUSICIAN_FEED_CAPACITY_UNAVAILABLE));
            assertThat(calls.get()).isZero();
            assertThat(bounded.getQueue()).isEmpty();
            assertNoPagePersisted();
        } finally {
            release.countDown();
            bounded.shutdownNow();
            assertThat(bounded.awaitTermination(1, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void interruptedCollectionCancelsRunningAndQueuedProvidersAndRestoresCallerInterrupt() throws Exception {
        MusicianFeedProviderExecutor bounded = new MusicianFeedProviderExecutor(1, 1, Executors.defaultThreadFactory());
        CountDownLatch started = new CountDownLatch(1), interrupted = new CountDownLatch(1);
        AtomicLong queuedCalls = new AtomicLong();
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var callerInterrupted = new java.util.concurrent.atomic.AtomicBoolean();
        MusicianFeedService service = service(List.of(provider("a-running", request -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException stopped) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return List.of();
        }), provider("z-queued", request -> { queuedCalls.incrementAndGet(); return List.of(); })), List.of(), bounded);
        Thread caller = new Thread(() -> {
            try {
                service.get(viewer, 10, null, List.of("TRACK"));
            } catch (Throwable stopped) {
                failure.set(stopped);
                callerInterrupted.set(Thread.currentThread().isInterrupted());
            }
        });
        try {
            caller.start();
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (bounded.getQueue().isEmpty() && System.nanoTime() < deadline) Thread.onSpinWait();
            assertThat(bounded.getQueue()).hasSize(1);
            caller.interrupt();
            caller.join(1_000);
            assertThat(caller.isAlive()).isFalse();
            assertThat(failure.get()).isInstanceOfSatisfying(SoundConnectException.class, unavailable ->
                    assertThat(unavailable.getErrorType()).isEqualTo(ErrorType.MUSICIAN_FEED_CAPACITY_UNAVAILABLE));
            assertThat(callerInterrupted.get()).isTrue();
            assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(queuedCalls.get()).isZero();
            assertThat(bounded.getQueue()).isEmpty();
            assertNoPagePersisted();
        } finally {
            caller.interrupt();
            bounded.shutdownNow();
            caller.join(1_000);
            assertThat(bounded.awaitTermination(1, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void assertNoPagePersisted() {
        verify(deliveries, never()).recordPage(any(), any(), any(), anyInt(), anyString(),
                anyLong(), anyList(), anyList(), any());
        verify(deliveries, never()).recordPageAndReplay(any(), any(), any(), anyInt(), anyString(),
                anyLong(), anyString(), anyInt(), anySet(), anyList(), anyList(), nullable(String.class),
                anyBoolean(), any());
    }

    @Test
    void requiredProviderCannotBeStarvedByASaturatedSharedOptionalPool() throws Exception {
        ThreadPoolExecutor saturated = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1));
        CountDownLatch release = new CountDownLatch(1);
        saturated.submit(() -> await(release));
        saturated.submit(() -> await(release));
        MusicianFeedCandidateProvider required = new MusicianFeedCandidateProvider() {
            @Override public String providerId() { return "required"; }
            @Override public Set<MusicianFeedItemType> supportedTypes() {
                return Set.of(MusicianFeedItemType.TRACK);
            }
            @Override public boolean optional() { return false; }
            @Override public List<MusicianFeedCandidate> findCandidates(MusicianFeedCandidateRequest request) {
                return List.of(candidate("TRACK:required", 1_000_000));
            }
        };
        try {
            MusicianFeedPageResponse page = service(List.of(required), List.of(), saturated)
                    .get(viewer, 10, null, List.of("TRACK"));
            assertThat(page.items()).extracting(MusicianFeedItemResponse::id)
                    .containsExactly("TRACK:required");
        } finally {
            release.countDown();
            saturated.shutdownNow();
        }
    }

    @Test
    void sessionTargetDedupePreventsSameNativeTargetFromResurfacingAsActivity() {
        UUID sharedTarget = UUID.randomUUID();
        MusicianFeedCandidate nativeItem = candidateForTarget("TRACK:native",
                MusicianFeedItemType.TRACK, sharedTarget, 1_000_000);
        MusicianFeedCandidate like = candidateForTarget("ACTIVITY_LIKE:like",
                MusicianFeedItemType.ACTIVITY_LIKE, sharedTarget, 900_000);
        MusicianFeedCandidate tail = candidateForTarget("TRACK:tail",
                MusicianFeedItemType.TRACK, UUID.randomUUID(), 800_000);
        MusicianFeedCandidateProvider provider = new MusicianFeedCandidateProvider() {
            @Override public String providerId() { return "mixed"; }
            @Override public Set<MusicianFeedItemType> supportedTypes() {
                return Set.of(MusicianFeedItemType.TRACK, MusicianFeedItemType.ACTIVITY_LIKE);
            }
            @Override public List<MusicianFeedCandidate> findCandidates(MusicianFeedCandidateRequest request) {
                return List.of(nativeItem, like, tail);
            }
        };
        MusicianFeedService service = service(List.of(provider));

        MusicianFeedPageResponse first = service.get(viewer, 1, null, List.of("TRACK", "ACTIVITY_LIKE"));
        MusicianFeedPageResponse second = service.get(viewer, 1, first.nextCursor(),
                List.of("TRACK", "ACTIVITY_LIKE"));

        assertThat(first.items()).extracting(MusicianFeedItemResponse::id).containsExactly("TRACK:native");
        assertThat(second.items()).extracting(MusicianFeedItemResponse::id).containsExactly("TRACK:tail");
    }

    @Test
    void qualifiedHistoryIsBatchedForTheViewerAndSessionAnchorAndSoftlyChangesSelection() {
        var seen = candidate("TRACK:seen", 1_000_000);
        var unseen = candidate("TRACK:unseen", 990_000);
        String seenKey = MusicianFeedDeliverySnapshot.targetKey(seen.target().type(), seen.target().id());
        String unseenKey = MusicianFeedDeliverySnapshot.targetKey(unseen.target().type(), unseen.target().id());
        when(deliveries.recentlyViewedTargetKeys(eq(viewer), any(), eq(NOW), eq(Set.of(seenKey, unseenKey))))
                .thenReturn(Set.of(seenKey));
        var page = service(List.of(provider("tracks", request -> List.of(seen, unseen))))
                .get(viewer, 1, null, List.of("TRACK"));
        assertThat(page.items()).extracting(MusicianFeedItemResponse::id).containsExactly("TRACK:unseen");
        verify(deliveries).recentlyViewedTargetKeys(viewer, page.feedSessionId(), NOW, Set.of(seenKey, unseenKey));
        assertThat(page.algorithmVersion()).isEqualTo("musician-v1.2.0");
    }

    @Test
    void historyReadFailureRetainsContentAndIsObservable() {
        when(deliveries.recentlyViewedTargetKeys(eq(viewer), any(), eq(NOW), anySet()))
                .thenThrow(new org.springframework.dao.QueryTimeoutException("Isolated test timeout"));
        var page = service(List.of(provider("tracks", request -> List.of(candidate("TRACK:retained", 1_000_000)))))
                .get(viewer, 20, null, List.of("TRACK"));
        assertThat(page.items()).extracting(MusicianFeedItemResponse::id).containsExactly("TRACK:retained");
        assertThat(metricsRegistry.get("soundconnect.musician.feed.history.unavailable").counter().count()).isEqualTo(1);
        assertThat(metricsRegistry.get("soundconnect.musician.feed.page.duration").tag("outcome", "success")
                .timer().count()).isEqualTo(1);
    }

    @Test
    void optionalFailureAndReturnedContentHaveBoundedOperationalMetrics() {
        var page = service(List.of(
                provider("tracks", request -> List.of(candidate("TRACK:ok", 1_000_000))),
                provider("unavailable", request -> { throw new IllegalStateException("Isolated source outage"); })))
                .get(viewer, 20, null, List.of("TRACK"));
        assertThat(page.items()).hasSize(1);
        assertThat(metricsRegistry.get("soundconnect.musician.feed.provider.results")
                .tags("provider", "tracks", "outcome", "success").counter().count()).isEqualTo(1);
        assertThat(metricsRegistry.get("soundconnect.musician.feed.provider.results")
                .tags("provider", "unavailable", "outcome", "failure").counter().count()).isEqualTo(1);
        assertThat(metricsRegistry.get("soundconnect.musician.feed.provider.execution")
                .tag("provider", "tracks").timer().count()).isEqualTo(1);
        assertThat(metricsRegistry.get("soundconnect.musician.feed.candidates")
                .tags("type", "TRACK", "lane", "FOLLOWING").counter().count()).isEqualTo(1);
        assertThat(metricsRegistry.get("soundconnect.musician.feed.response.items")
                .tag("type", "TRACK").counter().count()).isEqualTo(1);
        assertThat(metricsRegistry.getMeters()).allSatisfy(meter ->
                assertThat(meter.getId().getTags()).allSatisfy(tag -> assertThat(tag.getKey())
                        .isIn("provider", "outcome", "type", "lane")));
    }

    @Test
    void historyTransactionTimeoutAlsoDegradesWithoutDiscardingThePage() {
        when(deliveries.recentlyViewedTargetKeys(eq(viewer), any(), eq(NOW), anySet()))
                .thenThrow(new org.springframework.transaction.TransactionTimedOutException("Isolated transaction timeout"));
        var page = service(List.of(provider("tracks", request -> List.of(candidate("TRACK:retained", 1_000_000)))))
                .get(viewer, 20, null, List.of("TRACK"));
        assertThat(page.items()).hasSize(1);
        assertThat(metricsRegistry.get("soundconnect.musician.feed.history.unavailable").counter().count()).isEqualTo(1);
    }

    private MusicianFeedService service(List<MusicianFeedCandidateProvider> providers) {
        return service(providers, List.of(), executor);
    }

    private MusicianFeedService service(List<MusicianFeedCandidateProvider> providers,
                                        List<MusicianFeedSponsorshipProvider> sponsors,
                                        ExecutorService selectedExecutor) {
        return new MusicianFeedService(properties, guard,
                new MusicianFeedCursorCodec(new ObjectMapper(), properties), new MusicianFeedMixer(),
                feedback, restrictions, personalization, deliveries, providers, sponsors, selectedExecutor,
                Clock.fixed(NOW, ZoneOffset.UTC), new MusicianFeedMetrics(metricsRegistry));
    }

    private static MusicianFeedCandidateProvider provider(
            String id,
            java.util.function.Function<MusicianFeedCandidateRequest, List<MusicianFeedCandidate>> source
    ) {
        return new MusicianFeedCandidateProvider() {
            @Override public String providerId() { return id; }
            @Override public Set<MusicianFeedItemType> supportedTypes() { return Set.of(MusicianFeedItemType.TRACK); }
            @Override public List<MusicianFeedCandidate> findCandidates(MusicianFeedCandidateRequest request) {
                return source.apply(request);
            }
        };
    }

    private static MusicianFeedCandidate candidate(String id, long score) {
        UUID authorId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        var author = new MusicianFeedItemResponse.Author(authorId, UUID.randomUUID(), "MUSICIAN",
                "artist", "Artist", null, true);
        return new MusicianFeedCandidate(id, MusicianFeedItemType.TRACK, 1, NOW.minusSeconds(60),
                new MusicianFeedItemResponse.Reason(MusicianFeedReasonCode.FOLLOWING_PUBLICATION,
                        List.of(author), 0), author,
                new MusicianFeedItemResponse.Target("MEDIA", targetId), null, null,
                List.of(MusicianFeedFeedbackAction.HIDE),
                new MusicianFeedPayloads.Track(UUID.randomUUID(), targetId, "Track",
                        "https://cdn.soundconnect.test/track.mp3", null, null),
                score, 0, MusicianFeedLane.FOLLOWING, false);
    }

    private static MusicianFeedCandidate promotedCollab(String id, UUID targetId) {
        return promotedCollab(id, targetId, UUID.randomUUID());
    }

    private static MusicianFeedCandidate promotedCollab(String id, UUID targetId, UUID campaignId) {
        UUID authorId = UUID.randomUUID();
        var author = new MusicianFeedItemResponse.Author(authorId, UUID.randomUUID(), "MUSICIAN",
                "artist", "Artist", null, false);
        var listing = mock(com.berkayb.soundconnect.modules.collab.dto.response.CollabListingResponse.class);
        when(listing.id()).thenReturn(targetId);
        return new MusicianFeedCandidate(id, MusicianFeedItemType.COLLAB, 1, NOW.minusSeconds(60),
                new MusicianFeedItemResponse.Reason(MusicianFeedReasonCode.SPONSORED, List.of(), 0),
                author, new MusicianFeedItemResponse.Target("COLLAB", targetId), null,
                new MusicianFeedItemResponse.Promotion(campaignId, "Sponsored", "Başvur", "/collab"),
                List.of(MusicianFeedFeedbackAction.HIDE), new MusicianFeedPayloads.Collab(listing),
                2_000_000, 0, MusicianFeedLane.SYSTEM, false);
    }

    private static MusicianFeedCandidate candidateForTarget(String id, MusicianFeedItemType type,
                                                            UUID targetId, long score) {
        UUID authorId = UUID.randomUUID();
        var author = new MusicianFeedItemResponse.Author(authorId, UUID.randomUUID(), "MUSICIAN",
                "artist", "Artist", null, true);
        MusicianFeedReasonCode reason = type == MusicianFeedItemType.ACTIVITY_LIKE
                ? MusicianFeedReasonCode.FOLLOWED_USER_LIKED : MusicianFeedReasonCode.FOLLOWING_PUBLICATION;
        var trackPayload = new MusicianFeedPayloads.Track(UUID.randomUUID(), targetId, "Track",
                "https://cdn.soundconnect.test/track.mp3", null, null);
        Object payload = type == MusicianFeedItemType.ACTIVITY_LIKE
                ? new MusicianFeedPayloads.Activity("LIKE", author, MusicianFeedItemType.TRACK, trackPayload)
                : trackPayload;
        MusicianFeedItemResponse.Engagement engagement = type == MusicianFeedItemType.ACTIVITY_LIKE
                ? new MusicianFeedItemResponse.Engagement("MEDIA", targetId, 1, 0,
                false, true, true) : null;
        return new MusicianFeedCandidate(id, type, 1, NOW.minusSeconds(60),
                new MusicianFeedItemResponse.Reason(reason, List.of(author), 0), author,
                new MusicianFeedItemResponse.Target("MEDIA", targetId), engagement, null,
                List.of(MusicianFeedFeedbackAction.HIDE), payload, score, 0,
                MusicianFeedLane.FOLLOWING, false);
    }

    private static MusicianFeedSponsorshipProvider sponsorship(
            String id, Set<MusicianFeedItemType> types,
            java.util.function.Function<MusicianFeedCandidateRequest, List<MusicianFeedCandidate>> source) {
        return new MusicianFeedSponsorshipProvider() {
            @Override public String providerId() { return id; }
            @Override public Set<MusicianFeedItemType> supportedTypes() { return types; }
            @Override public List<MusicianFeedCandidate> findPlacements(MusicianFeedCandidateRequest request) {
                return source.apply(request);
            }
        };
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
