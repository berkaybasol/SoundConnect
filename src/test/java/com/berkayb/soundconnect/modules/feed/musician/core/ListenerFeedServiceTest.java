package com.berkayb.soundconnect.modules.feed.musician.core;

import com.berkayb.soundconnect.modules.feed.musician.api.*;
import com.berkayb.soundconnect.modules.feed.musician.announcement.MusicianFeedAnnouncementPlan;
import com.berkayb.soundconnect.modules.feed.musician.candidate.*;
import com.berkayb.soundconnect.modules.feed.musician.cursor.*;
import com.berkayb.soundconnect.modules.feed.musician.delivery.*;
import com.berkayb.soundconnect.modules.feed.musician.feedback.*;
import com.berkayb.soundconnect.modules.feed.musician.mixer.MusicianFeedMixer;
import com.berkayb.soundconnect.modules.feed.musician.moderation.MusicianFeedRestrictionGuard;
import com.berkayb.soundconnect.modules.feed.musician.personalization.*;
import com.berkayb.soundconnect.modules.feed.listener.core.ListenerFeedContentPolicy;
import com.berkayb.soundconnect.shared.exception.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ListenerFeedServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-14T09:00:00Z");
    private static final Set<MusicianFeedItemType> TYPES = Set.of(MusicianFeedItemType.TRACK);
    private final UUID viewer = UUID.randomUUID(), listener = UUID.randomUUID(), city = UUID.randomUUID();
    private final MusicianFeedProperties properties = new MusicianFeedProperties();
    private final MusicianFeedViewerGuard guard = mock(MusicianFeedViewerGuard.class);
    private final MusicianFeedFeedbackService feedback = mock(MusicianFeedFeedbackService.class);
    private final MusicianFeedRestrictionGuard restrictions = mock(MusicianFeedRestrictionGuard.class);
    private final MusicianFeedPersonalizationSource personalization = mock(MusicianFeedPersonalizationSource.class);
    private final MusicianFeedDeliveryService deliveries = mock(MusicianFeedDeliveryService.class);
    private final ListenerFeedContentPolicy contentPolicy = mock(ListenerFeedContentPolicy.class);
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final MusicianFeedCursorCodec codec = new MusicianFeedCursorCodec(new ObjectMapper(), properties);

    @BeforeEach void prepare() {
        when(contentPolicy.filterCandidates(eq(viewer), anyCollection())).thenAnswer(call -> List.copyOf(call.getArgument(1)));
        when(guard.requireListenerProfile(viewer)).thenReturn(listener);
        when(personalization.loadForListener(viewer, listener)).thenReturn(new MusicianFeedPersonalizationSnapshot(city, Set.of(), null));
        when(feedback.snapshot(viewer)).thenReturn(MusicianFeedFeedbackSnapshot.empty());
        when(feedback.forCandidates(eq(viewer), any(), anyCollection(), anyCollection())).thenAnswer(call -> call.getArgument(1));
        when(restrictions.filter(anyCollection())).thenAnswer(call -> List.copyOf(call.getArgument(0)));
        when(deliveries.snapshot(eq(viewer), any(), eq(NOW))).thenReturn(MusicianFeedDeliverySnapshot.empty(0));
        when(deliveries.recordPage(eq(viewer), any(), any(), eq(1), eq("listener-v1.0.0"), anyLong(), anyList(), anyList(), eq(NOW)))
                .thenAnswer(call -> {
                    List<MusicianFeedItemResponse> values = call.getArgument(6);
                    long start = call.getArgument(5);
                    var result = new ArrayList<MusicianFeedItemResponse>();
                    for (int i = 0; i < values.size(); i++) result.add(values.get(i).withDelivery(start + i, "test-proof"));
                    return result;
                });
    }

    @AfterEach void stopExecutor() { executor.shutdownNow(); }

    @Test void listenerUsesSharedPageEngineAndForwardsAudienceWithoutCompletionCapability() {
        var requested = new AtomicReference<MusicianFeedCandidateRequest>();
        var tracks = provider("tracks", TYPES, request -> { requested.set(request); return List.of(track(1), track(2)); });
        var completion = provider("completion", Set.of(MusicianFeedItemType.PROFILE_COMPLETION), request -> {
            throw new AssertionError("Listener must never request profile completion");
        });
        var page = service(tracks, completion).getForListener(viewer, 1, null, List.of("TRACK", "PROFILE_COMPLETION"));
        assertThat(page.items()).hasSize(1);
        assertThat(page.algorithmVersion()).isEqualTo("listener-v1.0.0");
        assertThat(page.hasMore()).isTrue();
        assertThat(requested.get().audience()).isEqualTo(BackstageFeedAudience.LISTENER);
        assertThat(requested.get().withProviderDeadline(42).audience()).isEqualTo(BackstageFeedAudience.LISTENER);
        assertThat(requested.get().viewerProfileId()).isEqualTo(listener);
        assertThat(requested.get().personalization().opportunityCityId()).isEqualTo(city);
        assertThat(requested.get().supportedTypes()).containsExactly(MusicianFeedItemType.TRACK);
        assertThat(codec.decodeForReplay(page.nextCursor(), viewer, TYPES, NOW, BackstageFeedAudience.LISTENER, listener).audience())
                .isEqualTo(BackstageFeedAudience.LISTENER);
        verify(guard, never()).requireMusicianProfile(any());
        verify(personalization, never()).load(any(), any());
    }

    @Test void wrongAudienceCursorCannotReachTheReplayJournalEvenAfterAnAccountRoleChange() {
        String musician = token(BackstageFeedAudience.MUSICIAN);
        assertThatThrownBy(() -> service().getForListener(viewer, 1, musician, List.of("TRACK")))
                .isInstanceOfSatisfying(SoundConnectException.class,
                        error -> assertThat(error.getErrorType()).isEqualTo(ErrorType.MUSICIAN_FEED_CURSOR_INVALID));
        verifyNoInteractions(deliveries, personalization, feedback);
    }

    @Test void musicianEndpointCannotReplayListenerCursorForTheSameViewer() {
        when(guard.requireMusicianProfile(viewer)).thenReturn(UUID.randomUUID());
        assertThatThrownBy(() -> service().get(viewer, 1, token(BackstageFeedAudience.LISTENER), List.of("TRACK")))
                .isInstanceOf(SoundConnectException.class);
        verifyNoInteractions(deliveries, personalization, feedback);
    }

    @Test void listenerReplayReusesTheValidatedSharedJournalWithoutRedrawingOrReadingPreferences() {
        String token = token(BackstageFeedAudience.LISTENER);
        var state = codec.decodeForReplay(token, viewer, TYPES, NOW, BackstageFeedAudience.LISTENER, listener);
        var replay = new MusicianFeedPageResponse(1, "listener-v1.0.0", state.feedSessionId(), NOW,
                List.of(track(3).toResponse().withDelivery(1, "saved-proof")), null, false);
        when(deliveries.replay(eq(viewer), eq(state.feedSessionId()), eq(1L), anyString(), eq(1), eq(TYPES), eq(NOW)))
                .thenReturn(Optional.of(replay));
        assertThat(service().getForListener(viewer, 1, token, List.of("TRACK"))).isSameAs(replay);
        verify(guard).requireListenerProfile(viewer);
        verifyNoInteractions(personalization, feedback);
    }

    @Test void rejectedListenerCannotReadCandidatesHistoryOrDelivery() {
        when(guard.requireListenerProfile(viewer)).thenThrow(new SoundConnectException(ErrorType.FORBIDDEN_ACCESS));
        assertThatThrownBy(() -> service().getForListener(viewer, 20, null, List.of("TRACK"))).isInstanceOf(SoundConnectException.class);
        verifyNoInteractions(personalization, deliveries, feedback);
    }

    @Test void changedAnchorListenerCannotReplayPreviouslyCommittedPages() {
        String cursor = token(BackstageFeedAudience.LISTENER);
        when(guard.requireListenerProfile(viewer)).thenReturn(UUID.randomUUID());
        assertThatThrownBy(() -> service().getForListener(viewer, 1, cursor, List.of("TRACK")))
                .isInstanceOfSatisfying(SoundConnectException.class,
                        error -> assertThat(error.getErrorType()).isEqualTo(ErrorType.MUSICIAN_FEED_CURSOR_INVALID));
        verifyNoInteractions(deliveries, personalization, feedback);
    }

    @Test void completionOnlyCapabilityIsRejectedWithoutLoadingData() {
        assertThatThrownBy(() -> service().getForListener(viewer, 20, null, List.of("PROFILE_COMPLETION")))
                .isInstanceOf(SoundConnectException.class);
        verifyNoInteractions(guard, deliveries, personalization, feedback);
    }

    @Test void listenerNeverStartsWithoutItsContentPolicy() {
        var engine = new MusicianFeedService(properties, guard, codec, new MusicianFeedMixer(), feedback, restrictions,
                personalization, deliveries, List.of(), List.of(), executor, Clock.fixed(NOW, ZoneOffset.UTC));
        assertThatThrownBy(() -> engine.getForListener(viewer, 20, null, List.of("TRACK")))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(deliveries, personalization, feedback);
    }

    @Test void opaqueSponsorshipCapabilityCannotReachProvidersOrDelivery() {
        assertThatThrownBy(() -> service().getForListener(viewer, 20, null, List.of("SPONSORED")))
                .isInstanceOf(SoundConnectException.class);
        assertThatThrownBy(() -> service().getForListener(viewer, 20, null, List.of("COLLAB")))
                .isInstanceOf(SoundConnectException.class);
        verifyNoInteractions(guard, deliveries, personalization, feedback, contentPolicy);
    }

    @Test void sourceCandidatesAreFilteredBeforeRankingHistoryOrDelivery() {
        var rejected = track(1);
        var allowed = track(2);
        when(contentPolicy.filterCandidates(eq(viewer), anyCollection())).thenAnswer(call -> {
            Collection<MusicianFeedCandidate> values = call.getArgument(1);
            return values.stream().filter(candidate -> !candidate.itemId().equals(rejected.itemId())).toList();
        });
        var page = service(provider("tracks", TYPES, request -> List.of(rejected, allowed)))
                .getForListener(viewer, 20, null, List.of("TRACK"));
        assertThat(page.items()).extracting(MusicianFeedItemResponse::id).containsExactly(allowed.itemId());
        verify(deliveries).recentlyViewedTargetKeys(eq(viewer), any(), eq(NOW),
                eq(Set.of(MusicianFeedDeliverySnapshot.targetKey(allowed.target().type(), allowed.target().id()))));
        verify(contentPolicy, times(2)).filterCandidates(eq(viewer), anyCollection());
    }

    @Test void cachedBusinessContentIsRejectedWithoutRegeneratingThePage() {
        String token = token(BackstageFeedAudience.LISTENER);
        var state = codec.decodeForReplay(token, viewer, TYPES, NOW, BackstageFeedAudience.LISTENER, listener);
        var replay = new MusicianFeedPageResponse(1, "listener-v1.0.0", state.feedSessionId(), NOW,
                List.of(track(3).toResponse().withDelivery(1, "saved-proof")), null, false);
        when(deliveries.replay(eq(viewer), eq(state.feedSessionId()), eq(1L), anyString(), eq(1), eq(TYPES), eq(NOW)))
                .thenReturn(Optional.of(replay));
        doThrow(new SoundConnectException(ErrorType.MUSICIAN_FEED_CURSOR_INVALID))
                .when(contentPolicy).requireReplayEligible(viewer, replay, NOW);
        assertThatThrownBy(() -> service().getForListener(viewer, 1, token, List.of("TRACK")))
                .isInstanceOfSatisfying(SoundConnectException.class,
                        error -> assertThat(error.getErrorType()).isEqualTo(ErrorType.MUSICIAN_FEED_CURSOR_INVALID));
        verify(contentPolicy).requireReplayEligible(viewer, replay, NOW);
        verifyNoInteractions(personalization, feedback);
    }

    @Test void concurrentlyCommittedReplayStillPassesListenerEligibilityBeforeReturning() {
        String token = token(BackstageFeedAudience.LISTENER);
        var state = codec.decodeForReplay(token, viewer, TYPES, NOW, BackstageFeedAudience.LISTENER, listener);
        var replay = new MusicianFeedPageResponse(1, "listener-v1.0.0", state.feedSessionId(), NOW,
                List.of(track(3).toResponse().withDelivery(1, "saved-proof")), null, false);
        when(deliveries.replay(eq(viewer), eq(state.feedSessionId()), eq(1L), anyString(), eq(1), eq(TYPES), eq(NOW)))
                .thenReturn(Optional.empty(), Optional.of(replay));
        when(deliveries.snapshot(viewer, state.feedSessionId(), NOW)).thenReturn(MusicianFeedDeliverySnapshot.empty(2));
        assertThat(service().getForListener(viewer, 1, token, List.of("TRACK"))).isSameAs(replay);
        verify(contentPolicy).requireReplayEligible(viewer, replay, NOW);
        verifyNoInteractions(personalization, feedback);
    }

    @Test void freshContinuationKeepsListenerContextAndWritesTheSharedReplayJournal() {
        var captured = new AtomicReference<MusicianFeedCandidateRequest>();
        var tracks = provider("tracks", TYPES, request -> {
            captured.set(request);
            return request.delivery().nextAbsolutePosition() == 0 ? List.of(track(1), track(2)) : List.of(track(2), track(3));
        });
        var engine = service(tracks);
        var first = engine.getForListener(viewer, 1, null, List.of("TRACK"));
        var firstState = codec.decodeForReplay(first.nextCursor(), viewer, TYPES, NOW, BackstageFeedAudience.LISTENER, listener);
        when(deliveries.snapshot(viewer, first.feedSessionId(), NOW)).thenReturn(MusicianFeedDeliverySnapshot.empty(1));
        when(deliveries.recordPageAndReplay(eq(viewer), eq(first.feedSessionId()), eq(NOW), eq(1),
                eq("listener-v1.0.0"), eq(1L), anyString(), eq(1), eq(TYPES), anyList(), anyList(), any(), anyBoolean(), eq(NOW)))
                .thenAnswer(call -> new MusicianFeedPageResponse(1, "listener-v1.0.0", first.feedSessionId(), NOW,
                        call.getArgument(9), call.getArgument(11), call.getArgument(12)));
        var next = engine.getForListener(viewer, 1, first.nextCursor(), List.of("TRACK"));
        assertThat(next.items()).hasSize(1);
        assertThat(next.items().getFirst().id()).isNotEqualTo(first.items().getFirst().id());
        assertThat(next.algorithmVersion()).isEqualTo("listener-v1.0.0");
        assertThat(captured.get().audience()).isEqualTo(BackstageFeedAudience.LISTENER);
        assertThat(captured.get().viewerProfileId()).isEqualTo(listener);
        assertThat(captured.get().personalization().opportunityCityId()).isEqualTo(city);
        assertThat(captured.get().announcementPlan()).isEqualTo(firstState.announcementPlan());
        assertThat(codec.decodeForReplay(next.nextCursor(), viewer, TYPES, NOW, BackstageFeedAudience.LISTENER, listener)
                .deliveredItemCount()).isEqualTo(2);
    }

    private String token(BackstageFeedAudience audience) {
        return codec.encode(new MusicianFeedCursorState(viewer, UUID.randomUUID(), NOW,
                new MusicianFeedCursorState.CursorPosition(1, NOW.minusSeconds(2), "TRACK:1"),
                1, 1, "frozen", MusicianFeedAnnouncementPlan.EMPTY, audience,
                audience == BackstageFeedAudience.LISTENER ? listener : null), TYPES);
    }

    private MusicianFeedService service(MusicianFeedCandidateProvider... providers) {
        var service = new MusicianFeedService(properties, guard, codec, new MusicianFeedMixer(), feedback, restrictions,
                personalization, deliveries, List.of(providers), List.of(), executor, Clock.fixed(NOW, ZoneOffset.UTC));
        service.setListenerContentPolicy(contentPolicy);
        return service;
    }

    private static MusicianFeedCandidateProvider provider(String id, Set<MusicianFeedItemType> types,
            java.util.function.Function<MusicianFeedCandidateRequest, List<MusicianFeedCandidate>> source) {
        return new MusicianFeedCandidateProvider() {
            public String providerId() { return id; }
            public Set<MusicianFeedItemType> supportedTypes() { return types; }
            public List<MusicianFeedCandidate> findCandidates(MusicianFeedCandidateRequest request) { return source.apply(request); }
        };
    }

    private static MusicianFeedCandidate track(int index) {
        UUID target = new UUID(1, index);
        var author = new MusicianFeedItemResponse.Author(new UUID(2, index), new UUID(3, index),
                "MUSICIAN", "artist" + index, "Artist", null, true);
        return new MusicianFeedCandidate("TRACK:" + target, MusicianFeedItemType.TRACK, 1, NOW.minusSeconds(index),
                new MusicianFeedItemResponse.Reason(MusicianFeedReasonCode.FOLLOWING_PUBLICATION, List.of(author), 0),
                author, new MusicianFeedItemResponse.Target("MEDIA", target), null, null,
                List.of(MusicianFeedFeedbackAction.HIDE), new MusicianFeedPayloads.Track(target, target, "Track",
                "https://cdn.test/track.mp3", null, null), 1_000_000, 0, MusicianFeedLane.FOLLOWING, false);
    }
}
