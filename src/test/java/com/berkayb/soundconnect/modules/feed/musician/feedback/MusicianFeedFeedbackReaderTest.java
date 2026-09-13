package com.berkayb.soundconnect.modules.feed.musician.feedback;

import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedItemResponse;
import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedItemType;
import com.berkayb.soundconnect.modules.feed.musician.candidate.MusicianFeedCandidate;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.time.Instant;
import java.util.*;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MusicianFeedFeedbackReaderTest {
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final MusicianFeedFeedbackReader reader = new MusicianFeedFeedbackReader(jdbc);
    private final UUID viewer = UUID.randomUUID();

    @Test
    void emptyCandidatesDoNotQueryOrReplaceExistingRankingState() {
        var ranking = new MusicianFeedFeedbackSnapshot(Set.of("TRACK:existing"), Set.of("MUSICIAN:existing"),
                Map.of(MusicianFeedItemType.TRACK, 4), "version");
        assertThat(reader.forCandidates(viewer, ranking, List.of(), List.of())).isSameAs(ranking);
        verifyNoInteractions(jdbc);
    }

    @Test
    void invalidViewerOrOversizedInputFailsBeforeAnyQuery() {
        assertThatThrownBy(() -> reader.ranking(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> reader.forCandidates(null, MusicianFeedFeedbackSnapshot.empty(),
                List.of(), List.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> reader.forCandidates(viewer, MusicianFeedFeedbackSnapshot.empty(),
                Collections.nCopies(MusicianFeedFeedbackReader.MAX_CANDIDATES + 1, candidate(1, null)), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc);
    }

    @Test
    void duplicateCandidatesAndSharedAuthorsAreDeduplicatedAcrossBoundedBatches() {
        var author = new MusicianFeedItemResponse.Author(UUID.randomUUID(), UUID.randomUUID(), "MUSICIAN",
                "artist", "Artist", null, true);
        var candidates = IntStream.range(0, 601).mapToObj(index -> candidate(index, author)).toList();
        var ranking = new MusicianFeedFeedbackSnapshot(Set.of("TRACK:existing"), Set.of("MUSICIAN:existing"),
                Map.of(MusicianFeedItemType.TRACK, 3), "unchanged");
        var result = reader.forCandidates(viewer, ranking, candidates, candidates);

        assertThat(result).isEqualTo(ranking);
        var parameters = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc, times(3)).query(anyString(), parameters.capture(), any(RowCallbackHandler.class));
        Set<String> probes = new HashSet<>();
        for (SqlParameterSource batch : parameters.getAllValues()) {
            assertThat(batch.getValue("viewerId")).isEqualTo(viewer);
            int count = 0;
            while (batch.hasValue("scope" + count)) {
                assertThat(probes.add(batch.getValue("action" + count) + "@" + batch.getValue("scope" + count)))
                        .isTrue();
                count++;
            }
            assertThat(count).isBetween(1, MusicianFeedFeedbackReader.PROBE_BATCH_SIZE);
        }
        assertThat(probes).hasSize(601 * 2 + 1);
    }

    private MusicianFeedCandidate candidate(int index, MusicianFeedItemResponse.Author author) {
        return new MusicianFeedCandidate("TRACK:" + index, MusicianFeedItemType.TRACK, 1, Instant.EPOCH,
                null, author, new MusicianFeedItemResponse.Target("MEDIA", UUID.randomUUID()),
                null, null, List.of(), null, 0, 0, null, false);
    }
}
