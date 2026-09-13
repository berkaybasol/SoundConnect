package com.berkayb.soundconnect.modules.feed.musician.announcement;

import com.berkayb.soundconnect.modules.feed.musician.api.*;
import com.berkayb.soundconnect.modules.feed.musician.candidate.*;
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
        when(history.qualifiedImpressionCounts(eq(VIEWER), anyList(), eq(NOW))).thenReturn(Map.of());
    }

    @Test void oldUnseenCandidatePastTheFirstBatchRemainsEligibleWithoutInheritingEarlyPriority() {
        List<AnnouncementResponse> recent = new ArrayList<>();
        Map<UUID, Long> recentCounts = new HashMap<>();
        for (int index = 1; index <= 160; index++) {
            var value = MusicianFeedAnnouncementSelectorTest.value(index, NOW.minusSeconds(index));
            recent.add(value);
            recentCounts.put(value.id(), 10L);
        }
        var old = MusicianFeedAnnouncementSelectorTest.value(161, NOW.minusSeconds(1000));
        when(source.findForFeedBatch(VIEWER, "MUSICIAN", NOW, null, 160))
                .thenReturn(new AnnouncementPage(recent, "older", true));
        when(source.findForFeedBatch(VIEWER, "MUSICIAN", NOW, "older", 160))
                .thenReturn(new AnnouncementPage(List.of(old), null, false));
        when(history.qualifiedImpressionCounts(VIEWER, recent.stream().map(AnnouncementResponse::id).toList(), NOW))
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
        verify(history, times(64)).qualifiedImpressionCounts(VIEWER, List.of(old.id()), NOW);
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
