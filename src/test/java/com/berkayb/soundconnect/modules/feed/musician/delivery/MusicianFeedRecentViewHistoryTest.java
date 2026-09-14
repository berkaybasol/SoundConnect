package com.berkayb.soundconnect.modules.feed.musician.delivery;

import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedItemType;
import com.berkayb.soundconnect.modules.feed.musician.candidate.MusicianFeedLane;
import com.berkayb.soundconnect.modules.feed.musician.core.MusicianFeedProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MusicianFeedRecentViewHistoryTest {
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final MusicianFeedDeliveryService service = new MusicianFeedDeliveryService(jdbc,
            mock(MusicianFeedDeliveryTokenCodec.class), new MusicianFeedProperties(), new ObjectMapper(),
            mock(MusicianFeedReplayVisibilityGuard.class));
    private final UUID viewer = UUID.randomUUID(), session = UUID.randomUUID();
    private final Instant anchor = Instant.parse("2026-09-14T12:00:00Z");

    @Test
    void noCandidatesNeedNoHistoryReadAndOversizedInternalBatchesAreRejected() {
        assertThat(service.recentlyViewedTargetKeys(viewer, session, anchor, Set.of())).isEmpty();
        Set<String> excessive = IntStream.rangeClosed(0, MusicianFeedDeliveryService.MAX_RECENT_VIEW_TARGETS)
                .mapToObj(index -> "MEDIA:" + index).collect(Collectors.toSet());
        assertThatThrownBy(() -> service.recentlyViewedTargetKeys(viewer, session, anchor, excessive))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc);
    }

    @Test
    void historyReadIsOneViewerScopedCandidateBatchWithStableServerTimeFence() throws Exception {
        UUID media = UUID.randomUUID();
        String key = MusicianFeedDeliverySnapshot.targetKey("MEDIA", media);
        Set<String> candidates = Set.of(key, "COLLAB:" + UUID.randomUUID());
        when(jdbc.query(anyString(), any(SqlParameterSource.class),
                org.mockito.ArgumentMatchers.<RowMapper<String>>any())).thenAnswer(call -> {
            String sql = call.getArgument(0);
            SqlParameterSource parameters = call.getArgument(1);
            assertThat(sql).contains("event.viewer_user_id=:viewerId", "event.event_type='IMPRESSION'",
                    "delivered.viewer_user_id=event.viewer_user_id", "event.recorded_at>:since",
                    "event.recorded_at<=:anchor", "delivered.feed_session_id<>:currentSessionId",
                    "delivered.campaign_id is null", "in (:targetKeys)")
                    .doesNotContain("client_occurred_at", "expires_at>");
            assertThat(parameters.getValue("viewerId")).isEqualTo(viewer);
            assertThat(parameters.getValue("currentSessionId")).isEqualTo(session);
            assertThat(parameters.getValue("anchor")).isEqualTo(Timestamp.from(anchor));
            assertThat(parameters.getValue("since")).isEqualTo(Timestamp.from(anchor.minusSeconds(86_400)));
            assertThat(parameters.getValue("targetKeys")).isEqualTo(candidates);
            ResultSet row = mock(ResultSet.class);
            when(row.getString("target_type")).thenReturn("MEDIA");
            when(row.getObject("target_id", UUID.class)).thenReturn(media);
            RowMapper<String> mapper = call.getArgument(2);
            return List.of(mapper.mapRow(row, 0));
        });

        Set<String> result = service.recentlyViewedTargetKeys(viewer, session, anchor, candidates);
        assertThat(result).containsExactly(key);
        assertThatThrownBy(() -> result.add("other")).isInstanceOf(UnsupportedOperationException.class);
        verify(jdbc, times(1)).query(anyString(), any(SqlParameterSource.class),
                org.mockito.ArgumentMatchers.<RowMapper<String>>any());
    }

    @Test
    void snapshotCarriesLastFiveOrganicAuthorsAcrossSystemAndPromotionalCards() throws Exception {
        UUID firstAuthor = UUID.randomUUID(), secondAuthor = UUID.randomUUID();
        List<ResultSet> rows = new ArrayList<>();
        rows.add(row(0, MusicianFeedItemType.TRACK, MusicianFeedLane.FOLLOWING, firstAuthor, null));
        rows.add(row(1, MusicianFeedItemType.TRACK, MusicianFeedLane.FOLLOWING, firstAuthor, null));
        rows.add(row(2, MusicianFeedItemType.PROFILE_COMPLETION, MusicianFeedLane.SYSTEM, null, null));
        rows.add(row(3, MusicianFeedItemType.ANNOUNCEMENT, MusicianFeedLane.SYSTEM, null, null));
        rows.add(row(4, MusicianFeedItemType.SPONSORED, MusicianFeedLane.GENERAL_DISCOVERY, null, UUID.randomUUID()));
        rows.add(row(5, MusicianFeedItemType.COLLAB, MusicianFeedLane.RELEVANT_OPPORTUNITY, secondAuthor, null));
        rows.add(row(6, MusicianFeedItemType.PROFILE, MusicianFeedLane.GENERAL_DISCOVERY, secondAuthor, null));
        rows.add(row(7, MusicianFeedItemType.TRACK, MusicianFeedLane.FOLLOWING, firstAuthor, null));
        rows.add(row(8, MusicianFeedItemType.EVENT, MusicianFeedLane.RELEVANT_OPPORTUNITY, secondAuthor, null));
        when(jdbc.query(eq(MusicianFeedDeliveryService.SNAPSHOT_SQL), any(SqlParameterSource.class),
                org.mockito.ArgumentMatchers.<RowMapper<Object>>any())).thenAnswer(call -> {
            SqlParameterSource parameters = call.getArgument(1);
            assertThat(parameters.getValue("now")).isEqualTo(Timestamp.from(anchor));
            assertThat(parameters.getValue("sessionId")).isEqualTo(session);
            RowMapper<Object> mapper = call.getArgument(2);
            List<Object> result = new ArrayList<>();
            for (int index = 0; index < rows.size(); index++) result.add(mapper.mapRow(rows.get(index), index));
            return result;
        });

        var snapshot = service.snapshot(viewer, session, anchor);
        assertThat(snapshot.recentOrganicHistory()).extracting(MusicianFeedDeliverySnapshot.OrganicHistoryEntry::itemType)
                .containsExactly(MusicianFeedItemType.TRACK, MusicianFeedItemType.COLLAB,
                        MusicianFeedItemType.PROFILE, MusicianFeedItemType.TRACK, MusicianFeedItemType.EVENT);
        assertThat(snapshot.recentOrganicHistory().getFirst().authorKey()).isEqualTo("MUSICIAN:" + firstAuthor);
        assertThat(snapshot.recentOrganicHistory().getLast().authorKey()).isEqualTo("MUSICIAN:" + secondAuthor);
        assertThat(snapshot.recentOrganicHistory().getLast().lane()).isEqualTo(MusicianFeedLane.RELEVANT_OPPORTUNITY);
        var enriched = snapshot.withRecentlyViewedTargetKeys(Set.of("MEDIA:" + UUID.randomUUID()));
        assertThat(enriched.recentOrganicHistory()).isEqualTo(snapshot.recentOrganicHistory());
        assertThat(enriched.deliveredAnnouncementIds()).isEqualTo(snapshot.deliveredAnnouncementIds());
        assertThat(enriched.nextAbsolutePosition()).isEqualTo(snapshot.nextAbsolutePosition());
        assertThat(snapshot.recentlyViewedTargetKeys()).isEmpty();
    }

    @Test
    void emptyOrRetiredLedgerLeavesNoSyntheticDiversityHistory() {
        when(jdbc.query(anyString(), any(SqlParameterSource.class),
                org.mockito.ArgumentMatchers.<RowMapper<Object>>any())).thenReturn(List.of());
        var snapshot = service.snapshot(viewer, session, anchor);
        assertThat(snapshot.recentOrganicHistory()).isEmpty();
        assertThat(snapshot.recentlyViewedTargetKeys()).isEmpty();
        assertThat(snapshot.nextAbsolutePosition()).isZero();
        assertThat(MusicianFeedDeliverySnapshot.empty(0).recentOrganicHistory()).isEmpty();
    }

    private ResultSet row(long position, MusicianFeedItemType type, MusicianFeedLane lane,
                          UUID author, UUID campaign) throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getString("item_id")).thenReturn(type + ":" + position);
        when(row.getString("item_type")).thenReturn(type.name());
        when(row.getString("feed_lane")).thenReturn(lane.name());
        when(row.getString("target_type")).thenReturn("MEDIA");
        when(row.getObject("target_id", UUID.class)).thenReturn(UUID.randomUUID());
        when(row.getLong("absolute_position")).thenReturn(position);
        when(row.getString("author_profile_type")).thenReturn(author == null ? null : "MUSICIAN");
        when(row.getObject("author_profile_id", UUID.class)).thenReturn(author);
        when(row.getObject("campaign_id", UUID.class)).thenReturn(campaign);
        return row;
    }
}
