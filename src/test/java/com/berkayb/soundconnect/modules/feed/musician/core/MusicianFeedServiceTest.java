package com.berkayb.soundconnect.modules.feed.musician.core;

import com.berkayb.soundconnect.modules.feed.musician.api.*;
import com.berkayb.soundconnect.modules.feed.musician.candidate.*;
import com.berkayb.soundconnect.modules.feed.musician.cursor.MusicianFeedCursorCodec;
import com.berkayb.soundconnect.modules.feed.musician.delivery.*;
import com.berkayb.soundconnect.modules.feed.musician.feedback.MusicianFeedFeedbackService;
import com.berkayb.soundconnect.modules.feed.musician.feedback.MusicianFeedFeedbackSnapshot;
import com.berkayb.soundconnect.modules.feed.musician.mixer.MusicianFeedMixer;
import com.berkayb.soundconnect.modules.feed.musician.personalization.MusicianFeedPersonalizationSnapshot;
import com.berkayb.soundconnect.modules.feed.musician.personalization.MusicianFeedPersonalizationSource;
import com.berkayb.soundconnect.modules.feed.musician.sponsor.MusicianFeedSponsorshipProvider;
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
    private final UUID viewer = UUID.randomUUID();
    private final UUID profile = UUID.randomUUID();
    private MusicianFeedViewerGuard guard;
    private MusicianFeedFeedbackService feedback;
    private MusicianFeedPersonalizationSource personalization;
    private MusicianFeedProperties properties;
    private MusicianFeedDeliveryService deliveries;
    private ExecutorService executor;
    private final AtomicLong deliveredCount = new AtomicLong();
    private final Set<String> deliveredIds = ConcurrentHashMap.newKeySet();
    private final Set<String> deliveredTargets = ConcurrentHashMap.newKeySet();
    private final Set<String> promotedTargets = ConcurrentHashMap.newKeySet();
    private final Set<UUID> campaignIds = ConcurrentHashMap.newKeySet();
    private final AtomicLong promotionCount = new AtomicLong();
    private final AtomicLong organicCount = new AtomicLong();
    private final AtomicLong organicCountAtLastPromotion = new AtomicLong();
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
        personalization = mock(MusicianFeedPersonalizationSource.class);
        properties = new MusicianFeedProperties();
        deliveries = mock(MusicianFeedDeliveryService.class);
        executor = Executors.newFixedThreadPool(4);
        deliveredCount.set(0);
        deliveredIds.clear();
        deliveredTargets.clear();
        promotedTargets.clear();
        campaignIds.clear();
        promotionCount.set(0);
        organicCount.set(0);
        organicCountAtLastPromotion.set(0);
        lastType.set(null);
        lastLane.set(null);
        lastPromoted.set(false);
        properties.setCursorSecret("unit-test-musician-feed-secret-at-least-32-bytes");
        when(guard.requireMusicianProfile(viewer)).thenReturn(profile);
        when(feedback.snapshot(viewer)).thenReturn(MusicianFeedFeedbackSnapshot.empty());
        when(personalization.load(viewer, profile)).thenReturn(MusicianFeedPersonalizationSnapshot.empty());
        when(deliveries.snapshot(eq(viewer), any(UUID.class), eq(NOW))).thenAnswer(ignored ->
                new MusicianFeedDeliverySnapshot(Set.copyOf(deliveredIds), Set.copyOf(deliveredTargets),
                        Set.copyOf(deliveredTargets), Set.copyOf(promotedTargets), Set.copyOf(campaignIds),
                        deliveredCount.get(), promotionCount.get(),
                        organicCountAtLastPromotion.get(), lastPromoted.get(), lastType.get(), lastLane.get()));
        when(deliveries.replay(eq(viewer), any(UUID.class), anyLong(), anyString(), anyInt(),
                anySet(), eq(NOW))).thenReturn(Optional.empty());
        when(deliveries.recordPage(eq(viewer), any(UUID.class), any(Instant.class), eq(1),
                eq("musician-v1.0.0"), anyLong(), anyList(), anyList(), eq(NOW))).thenAnswer(invocation -> {
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
                eq("musician-v1.0.0"), anyLong(), anyString(), anyInt(), anySet(), anyList(),
                anyList(), nullable(String.class), anyBoolean(), eq(NOW))).thenAnswer(invocation -> {
            UUID session = invocation.getArgument(1);
            Instant anchor = invocation.getArgument(2);
            long start = invocation.getArgument(5);
            @SuppressWarnings("unchecked") List<MusicianFeedItemResponse> items = invocation.getArgument(9);
            @SuppressWarnings("unchecked") List<MusicianFeedLane> lanes = invocation.getArgument(10);
            String nextCursor = invocation.getArgument(11);
            boolean hasMore = invocation.getArgument(12);
            List<MusicianFeedItemResponse> delivered = deliveries.recordPage(viewer, session, anchor, 1,
                    "musician-v1.0.0", start, items, lanes, NOW);
            return new MusicianFeedPageResponse(1, "musician-v1.0.0", session, NOW,
                    delivered, nextCursor, hasMore);
        });
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        executor.shutdownNow();
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
        assertThat(page.algorithmVersion()).isEqualTo("musician-v1.0.0");
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
                1, "musician-v1.0.0", firstPage.feedSessionId(), NOW,
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
        List<MusicianFeedCandidate> organic = java.util.stream.IntStream.range(0, 9)
                .mapToObj(index -> candidate("TRACK:" + index, 1_000_000 - index)).toList();
        UUID target = UUID.randomUUID();
        var promoted = promotedCollab("COLLAB:paid", target);
        MusicianFeedSponsorshipProvider sponsor = sponsorship("native", Set.of(MusicianFeedItemType.COLLAB),
                request -> List.of(promoted));

        MusicianFeedPageResponse page = service(List.of(provider("tracks", request -> organic)),
                List.of(sponsor), executor).get(viewer, 9, null, List.of("TRACK", "COLLAB"));

        assertThat(page.items()).anyMatch(value -> value.type() == MusicianFeedItemType.COLLAB
                && value.promotion() != null);
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

    private MusicianFeedService service(List<MusicianFeedCandidateProvider> providers) {
        return service(providers, List.of(), executor);
    }

    private MusicianFeedService service(List<MusicianFeedCandidateProvider> providers,
                                        List<MusicianFeedSponsorshipProvider> sponsors,
                                        ExecutorService selectedExecutor) {
        return new MusicianFeedService(properties, guard,
                new MusicianFeedCursorCodec(new ObjectMapper(), properties), new MusicianFeedMixer(),
                feedback, personalization, deliveries, providers, sponsors, selectedExecutor,
                Clock.fixed(NOW, ZoneOffset.UTC));
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
        UUID authorId = UUID.randomUUID();
        var author = new MusicianFeedItemResponse.Author(authorId, UUID.randomUUID(), "MUSICIAN",
                "artist", "Artist", null, false);
        var listing = mock(com.berkayb.soundconnect.modules.collab.dto.response.CollabListingResponse.class);
        when(listing.id()).thenReturn(targetId);
        return new MusicianFeedCandidate(id, MusicianFeedItemType.COLLAB, 1, NOW.minusSeconds(60),
                new MusicianFeedItemResponse.Reason(MusicianFeedReasonCode.SPONSORED, List.of(), 0),
                author, new MusicianFeedItemResponse.Target("COLLAB", targetId), null,
                new MusicianFeedItemResponse.Promotion(UUID.randomUUID(), "Sponsored", "Başvur", "/collab"),
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
