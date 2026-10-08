package com.berkayb.soundconnect.modules.feed.listener.core;

import com.berkayb.soundconnect.modules.feed.musician.api.*;
import com.berkayb.soundconnect.modules.feed.musician.candidate.*;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ListenerFeedContentPolicyTest {
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final ListenerFeedContentPolicy policy = new ListenerFeedContentPolicy(jdbc, new ObjectMapper().findAndRegisterModules());
    private static final UUID VIEWER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-14T12:00:00Z");

    @Test void followedListenerPublicSharesKeepTheirExistingCardAndIdentity() {
        var event = candidate(MusicianFeedItemType.EVENT_PROFILE_SHARE, "LISTENER", "EVENT_POST", Map.of("note", "Birlikte gidelim"));
        var table = candidate(MusicianFeedItemType.TABLEGROUP_PROFILE_SHARE, "LISTENER", "TABLE_GROUP_POST", Map.of("source", Map.of("title", "Caz masası")));
        var thought = candidate(MusicianFeedItemType.OVERTHINKING_PROFILE_SHARE, "LISTENER", "OVERTHINKING_PROFILE_SHARE", Map.of("source", Map.of("content", "Bu şarkı çok güzel")));
        when(jdbc.query(anyString(), anyMap(), org.mockito.ArgumentMatchers.<RowMapper<UUID>>any())).thenReturn(List.of(thought.target().id()));
        assertThat(policy.filterCandidates(VIEWER, List.of(event, table, thought))).containsExactly(event, table, thought);
        verify(jdbc, times(1)).query(anyString(), anyMap(), org.mockito.ArgumentMatchers.<RowMapper<UUID>>any());
    }

    @Test void studioAndCollabCannotEnterThroughAnAuthorAnActivityOrASharedSource() {
        var studio = candidate(MusicianFeedItemType.PROFILE, "STUDIO", "PROFILE", Map.of("profileType", "STUDIO"));
        var collab = candidate(MusicianFeedItemType.COLLAB, "MUSICIAN", "COLLAB", Map.of("title", "Vokalist arıyoruz"));
        var activity = candidate(MusicianFeedItemType.ACTIVITY_LIKE, "LISTENER", "PROFILE", Map.of("targetItemType", "PROFILE",
                "targetPayload", Map.of("profileType", "STUDIO")));
        var shared = candidate(MusicianFeedItemType.ACTIVITY_COMMENT, "LISTENER", "EVENT_POST", Map.of("targetPayload",
                Map.of("source", Map.of("targetType", "COLLAB"))));
        assertThat(policy.filterCandidates(VIEWER, List.of(studio, collab, activity, shared))).isEmpty();
        verifyNoInteractions(jdbc);
    }

    @Test void backstageAndUnknownAudienceMediaFailBeforeDelivery() {
        var backstage = candidate(MusicianFeedItemType.PROFILE_MEDIA, "MUSICIAN", "MEDIA", Map.of("contentAudience", "BACKSTAGE"));
        var unknown = candidate(MusicianFeedItemType.TRACK, "VENUE", "MEDIA", Map.of("contentAudience", "UNRECOGNIZED"));
        assertThat(policy.filterCandidates(VIEWER, List.of(backstage, unknown))).isEmpty();
        verifyNoInteractions(jdbc);
    }

    @Test void nativeAndNestedMediaUseTheCurrentDatabaseAudienceRatherThanTheSerializedValue() {
        UUID publicMedia = UUID.randomUUID(), studioOrBusinessMedia = UUID.randomUUID();
        when(jdbc.query(anyString(), anyMap(), org.mockito.ArgumentMatchers.<RowMapper<UUID>>any())).thenReturn(List.of(publicMedia));
        var publicTrack = media(publicMedia);
        var businessTrack = media(studioOrBusinessMedia);
        var nested = candidate(MusicianFeedItemType.ACTIVITY_LIKE, "LISTENER", "EVENT_POST",
                Map.of("targetItemType", "TRACK", "targetPayload", Map.of("mediaAssetId", studioOrBusinessMedia.toString(), "contentAudience", "MAINSTAGE")));
        assertThat(policy.filterCandidates(VIEWER, List.of(publicTrack, businessTrack, nested))).containsExactly(publicTrack);
        verify(jdbc, times(1)).query(anyString(), anyMap(), org.mockito.ArgumentMatchers.<RowMapper<UUID>>any());
    }

    @Test void changingAudienceInvalidatesAnAlreadySerializedPage() {
        UUID media = UUID.randomUUID();
        AtomicReference<List<UUID>> current = new AtomicReference<>(List.of(media));
        when(jdbc.query(anyString(), anyMap(), org.mockito.ArgumentMatchers.<RowMapper<UUID>>any())).thenAnswer(call -> current.get());
        var page = new MusicianFeedPageResponse(1, "listener-v1.0.0", UUID.randomUUID(), NOW,
                List.of(media(media).toResponse()), null, false);
        assertThatCode(() -> policy.requireReplayEligible(VIEWER, page, NOW)).doesNotThrowAnyException();
        current.set(List.of());
        assertThatThrownBy(() -> policy.requireReplayEligible(VIEWER, page, NOW.plusSeconds(1)))
                .isInstanceOf(SoundConnectException.class);
    }

    @Test void onlyListenerTargetedAnnouncementsAreAcceptedAndGenericSponsorsStayOutsideMainstage() {
        var listener = candidate(MusicianFeedItemType.ANNOUNCEMENT, null, "ANNOUNCEMENT", Map.of("targetProfiles", List.of("LISTENER")));
        var business = candidate(MusicianFeedItemType.ANNOUNCEMENT, null, "ANNOUNCEMENT", Map.of("targetProfiles", List.of("MUSICIAN", "VENUE")));
        var sponsor = candidate(MusicianFeedItemType.SPONSORED, null, "PROMOTION", Map.of("title", "Business CTA"));
        assertThat(policy.filterCandidates(VIEWER, List.of(listener, business, sponsor))).containsExactly(listener);
    }

    @Test void mediaReadsAreBatchedAndDuplicateTargetsAreResolvedOnce() {
        List<MusicianFeedCandidate> candidates = new ArrayList<>();
        List<Integer> sizes = new ArrayList<>();
        when(jdbc.query(anyString(), anyMap(), org.mockito.ArgumentMatchers.<RowMapper<UUID>>any())).thenAnswer(call -> {
            Map<String, List<UUID>> parameters = call.getArgument(1);
            List<UUID> ids = parameters.get("ids");
            sizes.add(ids.size());
            return ids;
        });
        for (int i = 0; i < 205; i++) candidates.add(media(UUID.randomUUID()));
        assertThat(policy.filterCandidates(VIEWER, candidates)).containsExactlyElementsOf(candidates);
        assertThat(sizes).containsExactly(200, 5);
    }

    private static MusicianFeedCandidate media(UUID id) {
        var value = candidate(MusicianFeedItemType.TRACK, "MUSICIAN", "MEDIA",
                Map.of("mediaAssetId", id.toString(), "contentAudience", "MAINSTAGE"));
        return new MusicianFeedCandidate("TRACK:" + id, value.type(), 1, NOW, value.reason(), value.author(),
                new MusicianFeedItemResponse.Target("MEDIA", id), null, null, List.of(), value.payload(),
                1_000_000, 0, MusicianFeedLane.FOLLOWING, false);
    }

    private static MusicianFeedCandidate candidate(MusicianFeedItemType type, String authorType, String targetType, Object payload) {
        UUID id = UUID.randomUUID();
        var author = authorType == null ? null : new MusicianFeedItemResponse.Author(UUID.randomUUID(), UUID.randomUUID(),
                authorType, "followed", "Followed listener", null, true);
        return new MusicianFeedCandidate(type.name() + ":" + id, type, 1, NOW,
                new MusicianFeedItemResponse.Reason(MusicianFeedReasonCode.FOLLOWING_PUBLICATION, List.of(), 0),
                author, new MusicianFeedItemResponse.Target(targetType, id), null, null, List.of(), payload,
                1_000_000, 0, MusicianFeedLane.FOLLOWING, false);
    }
}
