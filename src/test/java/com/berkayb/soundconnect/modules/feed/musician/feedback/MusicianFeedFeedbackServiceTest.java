package com.berkayb.soundconnect.modules.feed.musician.feedback;

import com.berkayb.soundconnect.modules.feed.musician.api.*;
import com.berkayb.soundconnect.modules.feed.musician.core.MusicianFeedViewerGuard;
import com.berkayb.soundconnect.modules.feed.musician.delivery.*;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class MusicianFeedFeedbackServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-11T13:00:00Z");
    private final UUID viewer = UUID.randomUUID();
    private MusicianFeedFeedbackRepository repository;
    private MusicianFeedViewerGuard guard;
    private MusicianFeedAuthorProfileGuard authorProfiles;
    private MusicianFeedDeliveryService deliveries;
    private MusicianFeedReportDispatcher reports;
    private MusicianFeedFeedbackLock feedbackLock;
    private MusicianFeedFeedbackService service;

    @BeforeEach
    void setUp() {
        repository = mock(MusicianFeedFeedbackRepository.class);
        guard = mock(MusicianFeedViewerGuard.class);
        authorProfiles = mock(MusicianFeedAuthorProfileGuard.class);
        deliveries = mock(MusicianFeedDeliveryService.class);
        reports = mock(MusicianFeedReportDispatcher.class);
        feedbackLock = mock(MusicianFeedFeedbackLock.class);
        service = new MusicianFeedFeedbackService(repository, guard, authorProfiles,
                deliveries, reports, feedbackLock,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(guard.requireMusicianProfile(viewer)).thenReturn(UUID.randomUUID());
        when(repository.countByViewerUserId(viewer)).thenReturn(0L);
        when(repository.save(any(MusicianFeedFeedback.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(deliveries.require(anyString(), eq(viewer), anyString(), eq(NOW))).thenAnswer(invocation -> {
            String itemId = invocation.getArgument(2);
            if (itemId == null || !itemId.contains(":")) {
                throw new SoundConnectException(ErrorType.BAD_REQUEST);
            }
            MusicianFeedItemType type = MusicianFeedItemType.valueOf(itemId.substring(0, itemId.indexOf(':')));
            return delivered(itemId, type);
        });
    }

    @Test
    void recordsIdempotentTypedItemFeedbackWithoutTrustingClientIdentity() {
        when(repository.findByViewerUserIdAndActionAndScopeKey(
                viewer, MusicianFeedFeedbackAction.REPORT, "ITEM:TRACK:abc")).thenReturn(Optional.empty());

        MusicianFeedFeedbackResponse response = service.recordItem(viewer, "TRACK:abc",
                new MusicianFeedFeedbackRequest(MusicianFeedFeedbackAction.REPORT, "  spam  "));

        ArgumentCaptor<MusicianFeedFeedback> saved = ArgumentCaptor.forClass(MusicianFeedFeedback.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getViewerUserId()).isEqualTo(viewer);
        assertThat(saved.getValue().getItemType()).isEqualTo(MusicianFeedItemType.TRACK);
        assertThat(saved.getValue().getReason()).isEqualTo("spam");
        assertThat(saved.getValue().getScopeKey()).isEqualTo("ITEM:TRACK:abc");
        assertThat(saved.getValue().getDeliveryId()).isNotNull();
        assertThat(response.recordedAt()).isEqualTo(NOW);
        verify(reports).report(eq(viewer), any(MusicianFeedDeliveredItem.class), eq("spam"), eq(NOW));
    }

    @Test
    void repeatedActionUpdatesTheExistingRowInsteadOfGrowingUnbounded() {
        MusicianFeedFeedback existing = MusicianFeedFeedback.item(viewer,
                MusicianFeedFeedbackAction.SHOW_LESS, "COLLAB:abc", MusicianFeedItemType.COLLAB,
                "first", NOW.minusSeconds(60));
        when(repository.findByViewerUserIdAndActionAndScopeKey(
                viewer, MusicianFeedFeedbackAction.SHOW_LESS, "ITEM:COLLAB:abc"))
                .thenReturn(Optional.of(existing));

        service.recordItem(viewer, "COLLAB:abc",
                new MusicianFeedFeedbackRequest(MusicianFeedFeedbackAction.SHOW_LESS, "second"));

        assertThat(existing.getReason()).isEqualTo("second");
        assertThat(existing.getUpdatedAt()).isEqualTo(NOW);
        verify(repository, never()).countByViewerUserId(any());
    }

    @Test
    void snapshotTurnsPersistedActionsIntoEligibilityAndRankingSignals() {
        UUID mutedAuthor = UUID.randomUUID();
        MusicianFeedFeedback hidden = MusicianFeedFeedback.item(viewer, MusicianFeedFeedbackAction.HIDE,
                "TRACK:hidden", MusicianFeedItemType.TRACK, null, NOW);
        MusicianFeedFeedback reported = MusicianFeedFeedback.item(viewer, MusicianFeedFeedbackAction.REPORT,
                "COLLAB:reported", MusicianFeedItemType.COLLAB, "unsafe", NOW);
        MusicianFeedFeedback lessOne = MusicianFeedFeedback.item(viewer, MusicianFeedFeedbackAction.SHOW_LESS,
                "TRACK:one", MusicianFeedItemType.TRACK, null, NOW);
        MusicianFeedFeedback lessTwo = MusicianFeedFeedback.item(viewer, MusicianFeedFeedbackAction.SHOW_LESS,
                "TRACK:two", MusicianFeedItemType.TRACK, null, NOW);
        MusicianFeedFeedback muted = MusicianFeedFeedback.mute(viewer, "VENUE", mutedAuthor, NOW);
        when(repository.findAllByViewerUserId(viewer))
                .thenReturn(List.of(hidden, reported, lessOne, lessTwo, muted));

        MusicianFeedFeedbackSnapshot snapshot = service.snapshot(viewer);

        assertThat(snapshot.hiddenItemIds()).containsExactlyInAnyOrder("TRACK:hidden", "COLLAB:reported");
        assertThat(snapshot.mutedAuthorKeys()).containsExactly(
                MusicianFeedFeedbackSnapshot.authorKey("VENUE", mutedAuthor));
        assertThat(snapshot.showLessCounts()).containsEntry(MusicianFeedItemType.TRACK, 2);
    }

    @Test
    void muteIsIdempotentAndUnmuteDeletesOnlyTheViewerScopedRow() {
        UUID author = UUID.randomUUID();
        when(authorProfiles.requireEligibleNotOwned(viewer, "venue", author)).thenReturn("VENUE");
        when(repository.findByViewerUserIdAndActionAndScopeKey(
                viewer, MusicianFeedFeedbackAction.MUTE_AUTHOR, "AUTHOR:VENUE:" + author))
                .thenReturn(Optional.empty());
        when(authorProfiles.normalize("VENUE", author)).thenReturn("VENUE");

        MusicianFeedFeedbackResponse response = service.mute(viewer, "venue", author);
        service.unmute(viewer, "VENUE", author);

        assertThat(response.action()).isEqualTo(MusicianFeedFeedbackAction.MUTE_AUTHOR);
        assertThat(response.authorProfileType()).isEqualTo("VENUE");
        assertThat(response.authorProfileId()).isEqualTo(author);
        verify(repository).deleteByViewerUserIdAndActionAndScopeKey(viewer,
                MusicianFeedFeedbackAction.MUTE_AUTHOR, "AUTHOR:VENUE:" + author);
    }

    @Test
    void rejectsWrongScopeActionsMalformedIdsAndUnsafeReasons() {
        assertThatThrownBy(() -> service.recordItem(viewer, "TRACK:abc",
                new MusicianFeedFeedbackRequest(MusicianFeedFeedbackAction.MUTE_AUTHOR, null)))
                .isInstanceOf(SoundConnectException.class);
        assertThatThrownBy(() -> service.recordItem(viewer, "not-an-item",
                new MusicianFeedFeedbackRequest(MusicianFeedFeedbackAction.HIDE, null)))
                .isInstanceOf(SoundConnectException.class);
        assertThatThrownBy(() -> service.recordItem(viewer, "TRACK:abc",
                new MusicianFeedFeedbackRequest(MusicianFeedFeedbackAction.REPORT, "bad\u0000reason")))
                .isInstanceOf(SoundConnectException.class);
        when(authorProfiles.requireEligibleNotOwned(viewer, "MUSICIAN", viewer))
                .thenThrow(SoundConnectException.class);
        assertThatThrownBy(() -> service.mute(viewer, "MUSICIAN", viewer))
                .isInstanceOf(SoundConnectException.class);
    }

    private MusicianFeedDeliveredItem delivered(String itemId, MusicianFeedItemType type) {
        return new MusicianFeedDeliveredItem(UUID.randomUUID(), viewer, UUID.randomUUID(), itemId, type,
                type == MusicianFeedItemType.COLLAB ? "COLLAB" : "MEDIA", UUID.randomUUID(),
                "MUSICIAN", UUID.randomUUID(), "FOLLOWING_PUBLICATION",
                Set.of(MusicianFeedFeedbackAction.HIDE, MusicianFeedFeedbackAction.SHOW_LESS,
                        MusicianFeedFeedbackAction.REPORT), 1, "musician-v1.0.0", 0, null, "{}",
                NOW.minusSeconds(1), NOW.plusSeconds(3600), NOW.plusSeconds(7200));
    }
}
