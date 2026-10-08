package com.berkayb.soundconnect.modules.feed.musician.delivery;

import com.berkayb.soundconnect.modules.feed.musician.core.MusicianFeedProperties;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MusicianFeedDeliveryCleanupTest {
    @Test
    void drainsRepeatedBoundedBatchesInsteadOfStoppingAfterOneDailyBatch() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        MusicianFeedProperties properties = new MusicianFeedProperties();
        properties.setCleanupBatchSize(1_000);
        properties.setCleanupMaxBatches(20);
        properties.setCleanupTimeBudget(Duration.ofSeconds(5));
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class)))
                .thenReturn(1_000, 1_000, 1_000, 500, 0, 0);
        var cleanup = new MusicianFeedDeliveryCleanup(jdbc, properties,
                Clock.fixed(Instant.parse("2026-09-11T12:00:00Z"), ZoneOffset.UTC));

        assertThat(cleanup.purge()).isEqualTo(3_500);
        verify(jdbc, times(6)).update(anyString(), any(MapSqlParameterSource.class));
    }
}
