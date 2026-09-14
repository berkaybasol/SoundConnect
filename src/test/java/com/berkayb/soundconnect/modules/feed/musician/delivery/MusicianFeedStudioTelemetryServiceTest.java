package com.berkayb.soundconnect.modules.feed.musician.delivery;

import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedTelemetryEventType;
import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedTelemetryRequest;
import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedItemType;
import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedFeedbackAction;
import com.berkayb.soundconnect.modules.feed.musician.core.MusicianFeedProperties;
import com.berkayb.soundconnect.modules.feed.musician.core.MusicianFeedViewerGuard;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MusicianFeedStudioTelemetryServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-14T12:00:00Z");
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final MusicianFeedDeliveryService deliveries = mock(MusicianFeedDeliveryService.class);
    private final MusicianFeedViewerGuard viewers = mock(MusicianFeedViewerGuard.class);
    private final UUID viewer = UUID.randomUUID();
    private final MusicianFeedTelemetryService service = new MusicianFeedTelemetryService(jdbc, deliveries, viewers,
            new MusicianFeedProperties(), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void studioImpressionIsRecordedAgainstItsAuthoritativeDeliveryInTheSharedTelemetryLedger() {
        UUID deliveryId = UUID.randomUUID();
        var delivery = new MusicianFeedDeliveredItem(deliveryId, viewer, UUID.randomUUID(), "TRACK:fixture",
                MusicianFeedItemType.TRACK, "MEDIA", UUID.randomUUID(), "MUSICIAN", UUID.randomUUID(),
                "FOLLOWING_PUBLICATION", Set.of(MusicianFeedFeedbackAction.HIDE), 1, "studio-v1.0.0", 0,
                null, "{}", NOW.minusSeconds(1), NOW.plusSeconds(60), NOW.plusSeconds(120));
        when(deliveries.require("signed.delivery", viewer, null, NOW)).thenReturn(delivery);
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class))).thenReturn(List.of(Map.of("id", UUID.randomUUID())));
        var request = request();
        var response = service.recordForStudio(viewer, request);
        assertThat(response.clientEventId()).isEqualTo(request.clientEventId());
        assertThat(response.eventType()).isEqualTo(MusicianFeedTelemetryEventType.IMPRESSION);
        assertThat(response.duplicate()).isFalse();
        var parameters = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).queryForList(contains("insert into tbl_musician_feed_telemetry_event"), parameters.capture());
        assertThat(parameters.getValue().getValue("deliveryId")).isEqualTo(deliveryId);
        assertThat(parameters.getValue().getValue("viewerId")).isEqualTo(viewer);
        verify(viewers).requireStudioProfile(viewer);
        verify(viewers, never()).requireMusicianProfile(viewer);
    }

    @Test
    void studioCannotReuseHistoricalBusinessOrMusicianFeedReceiptsForTelemetry() {
        for (String algorithm : List.of("musician-v1.2.0", "venue-v1.0.0", "listener-v1.0.0")) {
            var delivery = new MusicianFeedDeliveredItem(UUID.randomUUID(), viewer, UUID.randomUUID(), "TRACK:former-role",
                    MusicianFeedItemType.TRACK, "MEDIA", UUID.randomUUID(), "STUDIO", UUID.randomUUID(),
                    "FOLLOWING_PUBLICATION", Set.of(MusicianFeedFeedbackAction.HIDE), 1, algorithm, 0,
                    null, "{}", NOW.minusSeconds(1), NOW.plusSeconds(60), NOW.plusSeconds(120));
            when(deliveries.require("signed.delivery", viewer, null, NOW)).thenReturn(delivery);
            assertThatThrownBy(() -> service.recordForStudio(viewer, request()))
                    .isInstanceOfSatisfying(SoundConnectException.class,
                            failure -> assertThat(failure.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST));
        }
        verifyNoInteractions(jdbc);
    }

    @Test
    void unapprovedStudioIsRejectedBeforeReadingOrWritingTelemetry() {
        doThrow(new SoundConnectException(ErrorType.FORBIDDEN_ACCESS)).when(viewers).requireStudioProfile(viewer);
        assertThatThrownBy(() -> service.recordForStudio(viewer, request()))
                .isInstanceOfSatisfying(SoundConnectException.class,
                        failure -> assertThat(failure.getErrorType()).isEqualTo(ErrorType.FORBIDDEN_ACCESS));
        verifyNoInteractions(deliveries, jdbc);
    }

    @Test
    void admittedStudioStillNeedsItsOwnValidSignedDeliveryBeforeAnyTelemetryWrite() {
        doThrow(new SoundConnectException(ErrorType.BAD_REQUEST)).when(deliveries)
                .require("signed.delivery", viewer, null, NOW);
        assertThatThrownBy(() -> service.recordForStudio(viewer, request())).isInstanceOf(SoundConnectException.class);
        var order = inOrder(viewers, deliveries);
        order.verify(viewers).requireStudioProfile(viewer);
        order.verify(deliveries).require("signed.delivery", viewer, null, NOW);
        verify(viewers, never()).requireMusicianProfile(viewer);
        verifyNoInteractions(jdbc);
    }

    private MusicianFeedTelemetryRequest request() {
        return new MusicianFeedTelemetryRequest(UUID.randomUUID(), "signed.delivery", MusicianFeedTelemetryEventType.IMPRESSION, NOW);
    }
}
