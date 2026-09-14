package com.berkayb.soundconnect.modules.feed.musician.announcement;

import com.berkayb.soundconnect.modules.feed.musician.api.*;
import com.berkayb.soundconnect.modules.feed.musician.candidate.*;
import com.berkayb.soundconnect.modules.feed.musician.core.BackstageFeedAudience;
import com.berkayb.soundconnect.modules.feed.musician.delivery.MusicianFeedDeliverySnapshot;
import com.berkayb.soundconnect.modules.feed.musician.feedback.*;
import com.berkayb.soundconnect.modules.feed.musician.personalization.MusicianFeedPersonalizationSnapshot;
import com.berkayb.soundconnect.modules.promotion.announcement.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MusicianFeedAnnouncementCandidateProviderTest {
    private static final UUID VIEWER = new UUID(7, 3), SESSION = new UUID(3, 7);
    private static final Instant NOW = Instant.parse("2026-09-13T12:00:00Z");
    private final AnnouncementReadService source = mock(AnnouncementReadService.class);
    private final AnnouncementImpressionHistory history = mock(AnnouncementImpressionHistory.class);
    private final MusicianFeedFeedbackReader feedback = mock(MusicianFeedFeedbackReader.class);
    private final MusicianFeedAnnouncementCandidateProvider provider = new MusicianFeedAnnouncementCandidateProvider(source, history, feedback);

    @BeforeEach void prepare() {
        when(feedback.forCandidates(eq(VIEWER), any(), anyCollection(), anyCollection()))
                .thenReturn(MusicianFeedFeedbackSnapshot.empty());
        when(history.qualifiedImpressionHistory(eq(VIEWER), anyList(), eq(NOW))).thenReturn(Map.of());
    }

    @Test void oldUnseenCandidatePastTheFirstBatchRemainsEligibleWithoutInheritingEarlyPriority() {
        List<AnnouncementResponse> recent = new ArrayList<>();
        Map<UUID, AnnouncementImpressionHistory.QualifiedImpressions> recentCounts = new HashMap<>();
        for (int index = 1; index <= 160; index++) {
            var value = MusicianFeedAnnouncementSelectorTest.value(index, NOW.minusSeconds(index));
            recent.add(value);
            recentCounts.put(value.id(), new AnnouncementImpressionHistory.QualifiedImpressions(10, 0, NOW.minusSeconds(90_000)));
        }
        var old = MusicianFeedAnnouncementSelectorTest.value(161, NOW.minusSeconds(1000));
        when(source.findForFeedBatch(VIEWER, "MUSICIAN", NOW, null, 160))
                .thenReturn(new AnnouncementPage(recent, "older", true));
        when(source.findForFeedBatch(VIEWER, "MUSICIAN", NOW, "older", 160))
                .thenReturn(new AnnouncementPage(List.of(old), null, false));
        when(history.qualifiedImpressionHistory(VIEWER, recent.stream().map(AnnouncementResponse::id).toList(), NOW))
                .thenReturn(recentCounts);
        int oldSelections = 0;
        Set<List<UUID>> selectedPlans = new HashSet<>();
        // Fixed session seeds exercise the deterministic weighted selection;
        // neither wall-clock randomness nor a probabilistic test oracle is used.
        for (int session = 0; session < 64; session++) {
            var request = request(null, new UUID(30, session));
            var result = provider.findCandidates(request);
            assertThat(result).hasSize(3).allSatisfy(candidate ->
                    assertThat(candidate.announcementPlacement().gap()).isBetween(4, 8));
            assertThat(MusicianFeedCandidateContract.validateBatch(provider.providerId(), provider.supportedTypes(),
                    request, result, 8, false)).hasSize(3);
            List<UUID> ids = result.stream().map(candidate -> candidate.target().id()).toList();
            selectedPlans.add(ids);
            if (ids.contains(old.id())) oldSelections++;
        }
        assertThat(oldSelections).isBetween(1, 63);
        assertThat(selectedPlans).hasSizeGreaterThan(1);
        verify(source, times(64)).findForFeedBatch(VIEWER, "MUSICIAN", NOW, null, 160);
        verify(source, times(64)).findForFeedBatch(VIEWER, "MUSICIAN", NOW, "older", 160);
        verify(history, times(64)).qualifiedImpressionHistory(VIEWER, List.of(old.id()), NOW);
    }

    @Test void venueAudienceIsUsedForBothSelectionAndFrozenPlanReads() {
        var announcement = MusicianFeedAnnouncementSelectorTest.value(91, NOW.minusSeconds(60));
        when(source.findForFeedBatch(VIEWER, "VENUE", NOW, null, 160))
                .thenReturn(new AnnouncementPage(List.of(announcement), null, false));
        var venueRequest = request(null).withAudience(BackstageFeedAudience.VENUE);
        var initial = provider.findCandidates(venueRequest);
        assertThat(initial).singleElement().satisfies(value -> assertThat(value.target().id()).isEqualTo(announcement.id()));
        var plan = new MusicianFeedAnnouncementPlan(initial.stream().map(MusicianFeedCandidate::announcementPlacement).toList());
        when(source.findForFeedByIds(VIEWER, "VENUE", List.of(announcement.id()), NOW)).thenReturn(List.of(announcement));
        assertThat(provider.findCandidates(request(plan).withAudience(BackstageFeedAudience.VENUE))).hasSize(1);
        verify(source, never()).findForFeedBatch(eq(VIEWER), eq("MUSICIAN"), any(), any(), anyInt());
        verify(source, never()).findForFeedByIds(eq(VIEWER), eq("MUSICIAN"), anyList(), any());
    }

    @Test void studioAudienceIsUsedForBothSelectionAndFrozenPlanReads() {
        var announcement = MusicianFeedAnnouncementSelectorTest.value(92, NOW.minusSeconds(60));
        when(source.findForFeedBatch(VIEWER, "STUDIO", NOW, null, 160))
                .thenReturn(new AnnouncementPage(List.of(announcement), null, false));
        var initial = provider.findCandidates(request(null).withAudience(BackstageFeedAudience.STUDIO));
        assertThat(initial).singleElement().satisfies(value -> assertThat(value.target().id()).isEqualTo(announcement.id()));
        var plan = new MusicianFeedAnnouncementPlan(initial.stream().map(MusicianFeedCandidate::announcementPlacement).toList());
        when(source.findForFeedByIds(VIEWER, "STUDIO", List.of(announcement.id()), NOW)).thenReturn(List.of(announcement));
        assertThat(provider.findCandidates(request(plan).withAudience(BackstageFeedAudience.STUDIO))).hasSize(1);
        verify(source).findForFeedBatch(VIEWER, "STUDIO", NOW, null, 160);
        verify(source).findForFeedByIds(VIEWER, "STUDIO", List.of(announcement.id()), NOW);
        verifyNoMoreInteractions(source);
    }

    @Test void hiddenNewestIsExcludedBeforeTheThreeSlotsAreChosen() {
        var hidden = MusicianFeedAnnouncementSelectorTest.value(1, NOW);
        var visible = MusicianFeedAnnouncementSelectorTest.value(2, NOW.minusSeconds(1));
        when(source.findForFeedBatch(VIEWER, "MUSICIAN", NOW, null, 160))
                .thenReturn(new AnnouncementPage(List.of(hidden, visible), null, false));
        when(feedback.forCandidates(eq(VIEWER), any(), anyCollection(), anyCollection()))
                .thenReturn(new MusicianFeedFeedbackSnapshot(Set.of("ANNOUNCEMENT:" + hidden.id()), Set.of(), Map.of(), "0"));
        assertThat(provider.findCandidates(request(null))).singleElement().satisfies(value ->
                assertThat(value.target().id()).isEqualTo(visible.id()));
    }

    @Test void continuationUsesOnlyFrozenIdsWithoutReadingWeightsOrTheLatestWindow() {
        var older = MusicianFeedAnnouncementSelectorTest.value(40, NOW.minusSeconds(1000));
        var removed = new UUID(0, 41);
        var plan = new MusicianFeedAnnouncementPlan(List.of(
                new MusicianFeedAnnouncementPlan.Entry(removed, 1),
                new MusicianFeedAnnouncementPlan.Entry(older.id(), 7)));
        when(source.findForFeedByIds(VIEWER, "MUSICIAN", List.of(removed, older.id()), NOW)).thenReturn(List.of(older));
        assertThat(provider.findCandidates(request(plan))).singleElement().satisfies(value -> {
            assertThat(value.target().id()).isEqualTo(older.id());
            assertThat(value.announcementPlacement().gap()).isEqualTo(7);
        });
        verifyNoInteractions(history, feedback);
        verify(source, never()).findForFeedBatch(any(), anyString(), any(), any(), anyInt());
    }

    @Test void newPlanOmitsRecentAndDailyLimitedAnnouncementsWhilePreservingEligibleSeenWeights() {
        var cooling = MusicianFeedAnnouncementSelectorTest.value(1, NOW);
        var twice = MusicianFeedAnnouncementSelectorTest.value(2, NOW.minusSeconds(1));
        var cooldownBoundary = MusicianFeedAnnouncementSelectorTest.value(3, NOW.minusSeconds(2));
        var olderSeen = MusicianFeedAnnouncementSelectorTest.value(4, NOW.minusSeconds(3));
        var unseen = MusicianFeedAnnouncementSelectorTest.value(5, NOW.minusSeconds(4));
        var values = List.of(cooling, twice, cooldownBoundary, olderSeen, unseen);
        when(source.findForFeedBatch(VIEWER, "MUSICIAN", NOW, null, 160))
                .thenReturn(new AnnouncementPage(values, null, false));
        when(history.qualifiedImpressionHistory(VIEWER, values.stream().map(AnnouncementResponse::id).toList(), NOW))
                .thenReturn(Map.of(
                        cooling.id(), new AnnouncementImpressionHistory.QualifiedImpressions(1, 1, NOW.minusSeconds(21_599)),
                        twice.id(), new AnnouncementImpressionHistory.QualifiedImpressions(2, 2, NOW.minusSeconds(25_200)),
                        cooldownBoundary.id(), new AnnouncementImpressionHistory.QualifiedImpressions(1, 1, NOW.minusSeconds(21_600)),
                        olderSeen.id(), new AnnouncementImpressionHistory.QualifiedImpressions(10, 0, NOW.minusSeconds(90_000))));

        var result = provider.findCandidates(request(null));
        assertThat(result).extracting(value -> value.target().id())
                .containsExactlyInAnyOrder(cooldownBoundary.id(), olderSeen.id(), unseen.id());
        // The newest frequency-eligible announcement has already been seen, so no older candidate inherits its early slot.
        assertThat(result).allSatisfy(value -> assertThat(value.announcementPlacement().gap()).isBetween(4, 8));
    }

    @Test void singleRecentlySeenAnnouncementCanLeaveThePlanEmptyWithoutPreventingOrganicFeed() {
        var value = MusicianFeedAnnouncementSelectorTest.value(1, NOW);
        when(source.findForFeedBatch(VIEWER, "MUSICIAN", NOW, null, 160))
                .thenReturn(new AnnouncementPage(List.of(value), null, false));
        when(history.qualifiedImpressionHistory(VIEWER, List.of(value.id()), NOW))
                .thenReturn(Map.of(value.id(), new AnnouncementImpressionHistory.QualifiedImpressions(1, 1, NOW)));
        assertThat(provider.findCandidates(request(null))).isEmpty();
    }

    @Test void exhaustedSharedDeadlineNeverStartsTheFirstReadAndFailureNeverReturnsAPartialPlan() {
        assertThatThrownBy(() -> provider.findCandidates(request(null).withProviderDeadline(System.nanoTime() - 1)))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(source, history, feedback);
        var first = MusicianFeedAnnouncementSelectorTest.value(1, NOW);
        when(source.findForFeedBatch(VIEWER, "MUSICIAN", NOW, null, 160))
                .thenReturn(new AnnouncementPage(List.of(first), "later", true));
        when(source.findForFeedBatch(VIEWER, "MUSICIAN", NOW, "later", 160))
                .thenThrow(new IllegalStateException("Store unavailable"));
        assertThatThrownBy(() -> provider.findCandidates(request(null))).isInstanceOf(IllegalStateException.class);
    }

    private MusicianFeedCandidateRequest request(MusicianFeedAnnouncementPlan plan) {
        return request(plan, SESSION);
    }

    private MusicianFeedCandidateRequest request(MusicianFeedAnnouncementPlan plan, UUID session) {
        return new MusicianFeedCandidateRequest(VIEWER, new UUID(8, 1), session, NOW, NOW, 8,
                Set.of(MusicianFeedItemType.ANNOUNCEMENT), MusicianFeedPersonalizationSnapshot.empty(),
                MusicianFeedFeedbackSnapshot.empty(), MusicianFeedDeliverySnapshot.empty(0), plan);
    }
}
