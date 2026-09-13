package com.berkayb.soundconnect.modules.feed.musician.moderation;

import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MusicianFeedReportModerationServiceTest {
    private final MusicianFeedReportModerationRepository reports = mock(MusicianFeedReportModerationRepository.class);
    private final MusicianFeedReportAuditRepository audits = mock(MusicianFeedReportAuditRepository.class);
    private final MusicianFeedModerationPolicy policy = mock(MusicianFeedModerationPolicy.class);
    private final MusicianFeedReportModerationService service = new MusicianFeedReportModerationService(
            reports, audits, policy, mock(MusicianFeedReportCursorCodec.class));

    @Test
    void invalidCommandsAreRejectedBeforeReportLockOrAnyMutation() {
        for (String note : new String[]{"     ", " a   ", "x".repeat(501), "valid\u0001note", "valid\u0085note", "bad\ud800note"}) {
            assertThatThrownBy(() -> service.review(UUID.randomUUID(), UUID.randomUUID(),
                    new MusicianFeedReportReviewRequest(UUID.randomUUID(), 0L,
                            MusicianFeedReportDecision.DISMISS, note))).isInstanceOf(SoundConnectException.class);
        }
        assertThatThrownBy(() -> service.review(UUID.randomUUID(), UUID.randomUUID(), null)).isInstanceOf(SoundConnectException.class);
        assertThatThrownBy(() -> service.review(UUID.randomUUID(), UUID.randomUUID(),
                new MusicianFeedReportReviewRequest(UUID.randomUUID(), -1L,
                        MusicianFeedReportDecision.DISMISS, "Valid note"))).isInstanceOf(SoundConnectException.class);
        verifyNoInteractions(reports, audits, policy);
    }

    @Test
    void invalidViewerAndPageSizeCannotReadAnyReports() {
        assertThatThrownBy(() -> service.detail(null, UUID.randomUUID())).isInstanceOf(SoundConnectException.class);
        for (int limit : new int[]{0, -1, 51}) {
            assertThatThrownBy(() -> service.list(UUID.randomUUID(), null, null, limit, null))
                    .isInstanceOf(SoundConnectException.class);
        }
        verifyNoInteractions(reports, audits, policy);
    }
}
