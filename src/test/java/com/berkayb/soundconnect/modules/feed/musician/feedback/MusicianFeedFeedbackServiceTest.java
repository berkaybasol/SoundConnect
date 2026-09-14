package com.berkayb.soundconnect.modules.feed.musician.feedback;

import com.berkayb.soundconnect.modules.feed.musician.api.*;
import com.berkayb.soundconnect.modules.feed.musician.core.MusicianFeedViewerGuard;
import com.berkayb.soundconnect.modules.feed.musician.delivery.*;
import com.berkayb.soundconnect.modules.analytics.AnnouncementAnalyticsStore;
import com.berkayb.soundconnect.modules.promotion.announcement.AnnouncementAccess;
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
    private MusicianFeedFeedbackReader reader;
    private MusicianFeedFeedbackService service;

    @BeforeEach
    void setUp() {
        repository = mock(MusicianFeedFeedbackRepository.class);
        guard = mock(MusicianFeedViewerGuard.class);
        authorProfiles = mock(MusicianFeedAuthorProfileGuard.class);
        deliveries = mock(MusicianFeedDeliveryService.class);
        reports = mock(MusicianFeedReportDispatcher.class);
        feedbackLock = mock(MusicianFeedFeedbackLock.class);
        reader = mock(MusicianFeedFeedbackReader.class);
        service = new MusicianFeedFeedbackService(repository, guard, authorProfiles,
                deliveries, reports, feedbackLock, reader,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(guard.requireMusicianProfile(viewer)).thenReturn(UUID.randomUUID());
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
    void listenerActionsUseTheSameDeliveredFeedbackAndOwnPreferenceScope() {
        var request = new MusicianFeedFeedbackRequest(MusicianFeedFeedbackAction.HIDE, null, "signed.delivery");
        when(deliveries.require("signed.delivery", viewer, "TRACK:listener-target", NOW))
                .thenReturn(delivered("TRACK:listener-target", MusicianFeedItemType.TRACK, "listener-v1.0.0"));
        service.recordItemForListener(viewer, "TRACK:listener-target", request);
        UUID profile = UUID.randomUUID();
        when(authorProfiles.requireEligibleNotOwned(viewer, "LISTENER", profile)).thenReturn("LISTENER");
        when(authorProfiles.normalize("LISTENER", profile)).thenReturn("LISTENER");
        service.muteForListener(viewer, "LISTENER", profile);
        service.unmuteForListener(viewer, "LISTENER", profile);
        verify(guard, times(3)).requireListenerProfile(viewer);
        verify(guard, never()).requireMusicianProfile(viewer);
        verify(guard, never()).requireVenueProfile(viewer);
        verify(deliveries).require("signed.delivery", viewer, "TRACK:listener-target", NOW);
        verify(repository, times(2)).save(any(MusicianFeedFeedback.class));
        verify(repository).deleteByViewerUserIdAndActionAndScopeKey(viewer,
                MusicianFeedFeedbackAction.MUTE_AUTHOR, "AUTHOR:LISTENER:" + profile);
    }

    @Test
    void unauthorizedListenerCannotMutateThroughAnySharedWrite() {
        doThrow(new SoundConnectException(ErrorType.FORBIDDEN_ACCESS)).when(guard).requireListenerProfile(viewer);
        assertThatThrownBy(() -> service.recordItemForListener(viewer, "TRACK:ignored", null)).isInstanceOf(SoundConnectException.class);
        assertThatThrownBy(() -> service.muteForListener(viewer, "LISTENER", UUID.randomUUID())).isInstanceOf(SoundConnectException.class);
        assertThatThrownBy(() -> service.unmuteForListener(viewer, "LISTENER", UUID.randomUUID())).isInstanceOf(SoundConnectException.class);
        verifyNoInteractions(repository, deliveries, reports, authorProfiles, feedbackLock);
    }

    @Test
    void listenerRejectsFormerMusicianOrVenueDeliveryBeforePersistingOrEchoingItsTarget() {
        for (String algorithm : List.of("musician-v1.2.0", "venue-v1.0.0")) {
            when(deliveries.require("signed.old-role", viewer, "TRACK:old-role", NOW))
                    .thenReturn(delivered("TRACK:old-role", MusicianFeedItemType.TRACK, algorithm));
            assertThatThrownBy(() -> service.recordItemForListener(viewer, "TRACK:old-role",
                    new MusicianFeedFeedbackRequest(MusicianFeedFeedbackAction.HIDE, null, "signed.old-role")))
                    .isInstanceOfSatisfying(SoundConnectException.class,
                            error -> assertThat(error.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST));
        }
        verifyNoInteractions(repository, reports, feedbackLock, authorProfiles);
    }

    @Test
    void listenerCannotMuteOrUnmuteBusinessStudioProfiles() {
        UUID studio = UUID.randomUUID();
        when(authorProfiles.normalize("STUDIO", studio)).thenReturn("STUDIO");
        assertThatThrownBy(() -> service.muteForListener(viewer, "STUDIO", studio)).isInstanceOf(SoundConnectException.class);
        assertThatThrownBy(() -> service.unmuteForListener(viewer, "STUDIO", studio)).isInstanceOf(SoundConnectException.class);
        verify(authorProfiles, never()).requireEligibleNotOwned(any(), any(), any());
        verifyNoInteractions(repository, reports, deliveries, feedbackLock);
    }

    @Test
    void venueActionsRetainDeliveredTargetValidationAndTheSameFeedbackStorage() {
        var request = new MusicianFeedFeedbackRequest(MusicianFeedFeedbackAction.HIDE, null, "signed.delivery");
        service.recordItemForVenue(viewer, "TRACK:venue-target", request);
        UUID profile = UUID.randomUUID();
        when(authorProfiles.requireEligibleNotOwned(viewer, "MUSICIAN", profile)).thenReturn("MUSICIAN");
        when(authorProfiles.normalize("MUSICIAN", profile)).thenReturn("MUSICIAN");
        service.muteForVenue(viewer, "MUSICIAN", profile);
        service.unmuteForVenue(viewer, "MUSICIAN", profile);
        verify(guard, times(3)).requireVenueProfile(viewer);
        verify(guard, never()).requireMusicianProfile(viewer);
        verify(deliveries).require("signed.delivery", viewer, "TRACK:venue-target", NOW);
        verify(repository, times(2)).save(any(MusicianFeedFeedback.class));
        verify(repository).deleteByViewerUserIdAndActionAndScopeKey(viewer,
                MusicianFeedFeedbackAction.MUTE_AUTHOR, "AUTHOR:MUSICIAN:" + profile);
    }

    @Test
    void unauthorizedVenueActionsFailBeforeTokensTargetsLocksOrPersistence() {
        doThrow(new SoundConnectException(ErrorType.FORBIDDEN_ACCESS)).when(guard).requireVenueProfile(viewer);
        assertThatThrownBy(() -> service.recordItemForVenue(viewer, "TRACK:ignored", null))
                .isInstanceOf(SoundConnectException.class);
        assertThatThrownBy(() -> service.muteForVenue(viewer, "MUSICIAN", UUID.randomUUID()))
                .isInstanceOf(SoundConnectException.class);
        assertThatThrownBy(() -> service.unmuteForVenue(viewer, "MUSICIAN", UUID.randomUUID()))
                .isInstanceOf(SoundConnectException.class);
        verifyNoInteractions(repository, deliveries, reports, authorProfiles, feedbackLock);
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
        verify(repository).save(existing);
    }

    @Test
    void announcementHideKeepsItsIdentityAcrossEditsAndReportsAuthoritativeAnalyticsWithThatReceipt() {
        var analytics = mock(AnnouncementAnalyticsStore.class);
        var access = mock(AnnouncementAccess.class);
        service = new MusicianFeedFeedbackService(repository, guard, authorProfiles, deliveries, reports, feedbackLock,
                reader, Clock.fixed(NOW, ZoneOffset.UTC), analytics, access);
        UUID announcement = UUID.randomUUID(), otherAnnouncement = UUID.randomUUID();
        Map<String, MusicianFeedFeedback> savedRows = new HashMap<>();
        when(repository.findByViewerUserIdAndActionAndScopeKey(eq(viewer), eq(MusicianFeedFeedbackAction.HIDE), anyString()))
                .thenAnswer(call -> Optional.ofNullable(savedRows.get(call.getArgument(2))));
        when(repository.save(any(MusicianFeedFeedback.class))).thenAnswer(call -> {
            MusicianFeedFeedback row = call.getArgument(0);
            savedRows.put(row.getScopeKey(), row);
            return row;
        });
        when(deliveries.require(anyString(), eq(viewer), startsWith("ANNOUNCEMENT:"), eq(NOW))).thenAnswer(call -> {
            String item = call.getArgument(2);
            return new MusicianFeedDeliveredItem(UUID.randomUUID(), viewer, UUID.randomUUID(), item,
                    MusicianFeedItemType.ANNOUNCEMENT, "ANNOUNCEMENT", UUID.fromString(item.substring(13)), null, null,
                    "PLATFORM_ANNOUNCEMENT", Set.of(MusicianFeedFeedbackAction.HIDE), 1, "announcement-test", 0, null,
                    "{}", NOW, NOW.plusSeconds(100), NOW.plusSeconds(200));
        });
        var hide = new MusicianFeedFeedbackRequest(MusicianFeedFeedbackAction.HIDE, null, "real-delivery-token");
        service.recordItem(viewer, "ANNOUNCEMENT:" + announcement, hide);
        UUID firstReceipt = savedRows.get("ITEM:ANNOUNCEMENT:" + announcement).getId();
        service.recordItem(viewer, "ANNOUNCEMENT:" + announcement, hide); // later delivery after an edit, same aggregate id
        service.recordItem(viewer, "ANNOUNCEMENT:" + otherAnnouncement, hide);
        assertThat(savedRows).hasSize(2);
        assertThat(savedRows.get("ITEM:ANNOUNCEMENT:" + announcement).getId()).isEqualTo(firstReceipt);
        verify(analytics).recordEngagement(viewer, announcement, firstReceipt, AnnouncementAnalyticsStore.EngagementMetric.HIDE, NOW);
        verify(access, times(2)).requireVisible(viewer, announcement);
        verifyNoInteractions(reports);
        assertThatThrownBy(() -> service.recordItem(viewer, "ANNOUNCEMENT:" + announcement,
                new MusicianFeedFeedbackRequest(MusicianFeedFeedbackAction.SHOW_LESS, null, "real-delivery-token")))
                .isInstanceOf(SoundConnectException.class);
    }

    @Test
    void deletedArchivedOrRetargetedAnnouncementCannotPersistHideOrAttributionFromAnOldDelivery() {
        var analytics = mock(AnnouncementAnalyticsStore.class);
        var access = mock(AnnouncementAccess.class);
        service = new MusicianFeedFeedbackService(repository, guard, authorProfiles, deliveries, reports, feedbackLock,
                reader, Clock.fixed(NOW, ZoneOffset.UTC), analytics, access);
        doThrow(new SoundConnectException(ErrorType.ANNOUNCEMENT_NOT_FOUND)).when(access).requireVisible(eq(viewer), any());
        assertThatThrownBy(() -> service.recordItem(viewer, "ANNOUNCEMENT:" + UUID.randomUUID(),
                new MusicianFeedFeedbackRequest(MusicianFeedFeedbackAction.HIDE, null, "old-valid-proof")))
                .isInstanceOfSatisfying(SoundConnectException.class,
                        error -> assertThat(error.getErrorType()).isEqualTo(ErrorType.ANNOUNCEMENT_NOT_FOUND));
        verify(access).requireVisible(eq(viewer), any());
        verifyNoInteractions(repository, analytics, reports);
    }

    @Test
    void legacyConstructorCannotSilentlyAcceptAnnouncementFeedbackWithoutItsGuard() {
        assertThatThrownBy(() -> service.recordItem(viewer, "ANNOUNCEMENT:" + UUID.randomUUID(),
                new MusicianFeedFeedbackRequest(MusicianFeedFeedbackAction.HIDE, null, "valid-proof")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("dependencies are unavailable");
        verifyNoInteractions(repository, reports);
    }

    @Test
    void snapshotReadsBoundedRankingWithoutHydratingThePreferenceHistory() {
        var ranking = new MusicianFeedFeedbackSnapshot(Set.of(), Set.of(),
                Map.of(MusicianFeedItemType.TRACK, 10), "capped-ranking");
        when(reader.ranking(viewer)).thenReturn(ranking);

        MusicianFeedFeedbackSnapshot snapshot = service.snapshot(viewer);

        assertThat(snapshot).isEqualTo(ranking);
        verifyNoInteractions(repository);
    }

    @Test
    void anonymousSnapshotRequestsNeverReadPreferences() {
        assertThatThrownBy(() -> service.snapshot(null))
                .isInstanceOfSatisfying(SoundConnectException.class,
                        failure -> assertThat(failure.getErrorType()).isEqualTo(ErrorType.UNAUTHORIZED));
        assertThatThrownBy(() -> service.forCandidates(null, MusicianFeedFeedbackSnapshot.empty(),
                List.of(), List.of()))
                .isInstanceOfSatisfying(SoundConnectException.class,
                        failure -> assertThat(failure.getErrorType()).isEqualTo(ErrorType.UNAUTHORIZED));
        verifyNoInteractions(reader, repository);
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
        return delivered(itemId, type, "musician-v1.0.0");
    }

    private MusicianFeedDeliveredItem delivered(String itemId, MusicianFeedItemType type, String algorithm) {
        return new MusicianFeedDeliveredItem(UUID.randomUUID(), viewer, UUID.randomUUID(), itemId, type,
                type == MusicianFeedItemType.COLLAB ? "COLLAB" : "MEDIA", UUID.randomUUID(),
                "MUSICIAN", UUID.randomUUID(), "FOLLOWING_PUBLICATION",
                Set.of(MusicianFeedFeedbackAction.HIDE, MusicianFeedFeedbackAction.SHOW_LESS,
                        MusicianFeedFeedbackAction.REPORT), 1, algorithm, 0, null, "{}",
                NOW.minusSeconds(1), NOW.plusSeconds(3600), NOW.plusSeconds(7200));
    }
}
