package com.berkayb.soundconnect.modules.feed.musician.delivery;

import com.berkayb.soundconnect.modules.feed.musician.api.*;
import com.berkayb.soundconnect.modules.feed.musician.moderation.MusicianFeedRestrictionGuard;
import com.berkayb.soundconnect.modules.overthinking.service.OverthinkingPostService;
import com.berkayb.soundconnect.modules.promotion.announcement.AnnouncementReadService;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MusicianFeedAnnouncementReplayTest {
    private static final Instant NOW = Instant.parse("2026-09-13T12:00:00Z");
    private final UUID viewer = new UUID(50, 1), announcement = new UUID(60, 1);
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final AnnouncementReadService access = mock(AnnouncementReadService.class);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final MusicianFeedReplayVisibilityGuard guard = new MusicianFeedReplayVisibilityGuard(jdbc, mapper,
            mock(OverthinkingPostService.class), mock(MusicianFeedRestrictionGuard.class), access);

    @Test void currentlyVisibleReplayPreservesExactPayloadWhileRecheckingTheAuthenticatedAudience() throws Exception {
        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Boolean.class))).thenReturn(false);
        var original = page(announcement);
        var replay = mapper.readValue(mapper.writeValueAsBytes(original), MusicianFeedPageResponse.class);
        String before = mapper.writeValueAsString(replay);
        guard.requireVisible(viewer, replay, NOW);
        assertThat(mapper.writeValueAsString(replay)).isEqualTo(before);
        verify(access).requireVisible(viewer, announcement);
    }

    @Test void archivedOrRetargetedAnnouncementsInvalidateTheReplayInsteadOfLeakingTheOldPage() {
        doThrow(new SoundConnectException(ErrorType.BAD_REQUEST)).when(access).requireVisible(viewer, announcement);
        assertInvalid(() -> guard.requireVisible(viewer, page(announcement), NOW));
    }

    @Test void durableHideUsesTheSameAnnouncementIdentityAfterAnEditAndCannotCrossTargets() {
        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Boolean.class))).thenAnswer(call -> {
            MapSqlParameterSource parameters = call.getArgument(1);
            assertThat(parameters.getValue("viewerId")).isEqualTo(viewer);
            assertThat(parameters.getValues().values()).contains("ITEM:ANNOUNCEMENT:" + announcement);
            return true;
        });
        assertInvalid(() -> guard.requireVisible(viewer, page(announcement), NOW));
        assertInvalid(() -> guard.requireVisible(viewer, page(new UUID(60, 2)), NOW));
    }

    private MusicianFeedPageResponse page(UUID payloadId) {
        var item = new MusicianFeedItemResponse("ANNOUNCEMENT:" + announcement, MusicianFeedItemType.ANNOUNCEMENT,
                1, NOW.minusSeconds(20), 2, "original-proof",
                new MusicianFeedItemResponse.Reason(MusicianFeedReasonCode.PLATFORM_ANNOUNCEMENT, List.of(), 0), null,
                new MusicianFeedItemResponse.Target("ANNOUNCEMENT", announcement), null, null,
                List.of(MusicianFeedFeedbackAction.HIDE), Map.of("id", payloadId, "version", 0, "title", "Original title", "body", "Original body"));
        return new MusicianFeedPageResponse(1, "announcement-test", new UUID(80, 1), NOW, List.of(item), "same-cursor", true);
    }

    private static void assertInvalid(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(SoundConnectException.class,
                failure -> assertThat(failure.getErrorType()).isEqualTo(ErrorType.MUSICIAN_FEED_CURSOR_INVALID));
    }
}
