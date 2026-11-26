package com.berkayb.soundconnect.modules.analytics.report;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.admin.security.AdminPanelReadAccess;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminAnalyticsReportSourcesTest {
    private final ReportHistory history = mock(ReportHistory.class);
    private final UserDetailsImpl admin = mock(UserDetailsImpl.class);
    private final AdminAnalyticsReports reports = new AdminAnalyticsReports(
            mock(AdminPanelReadAccess.class), history, mock(Ga4Reports.class));
    private final YearMonth current = YearMonth.now(AdminAnalyticsReports.ZONE);
    private final YearMonth transition = current.minusMonths(1);

    @Test void pastTransitionAndFollowingMonthsReturnOnlyTheirOwnSourceAndCoverage() {
        Instant oldStart = transition.minusMonths(2).atDay(1).atStartOfDay(AdminAnalyticsReports.ZONE).toInstant();
        Instant cutover = transition.atDay(15).atStartOfDay(AdminAnalyticsReports.ZONE).toInstant();
        var historical = new AnalyticsReport.Peaks(new AnalyticsReport.Peak(15, oldStart), null, 10);
        var firstParty = new AnalyticsReport.Peaks(new AnalyticsReport.Peak(2, cutover.plusSeconds(360)), null, 1);
        when(history.started()).thenReturn(oldStart);
        when(history.liveStarted()).thenReturn(cutover);
        when(history.peaks(any(), any(), eq("ALL"), eq(false))).thenReturn(historical);
        when(history.peaks(any(), any(), eq("ALL"), eq(true))).thenReturn(firstParty);

        var previous = reports.get(admin, transition.minusMonths(1).toString(), null);
        assertThat(previous.liveSource()).isEqualTo("SOUNDCONNECT");
        assertThat(previous.peakSource()).isEqualTo("GA4");
        assertThat(previous.peakCoverageStart()).isEqualTo(oldStart);
        assertThat(previous.peaks().members().count()).isEqualTo(15);
        for (YearMonth month : new YearMonth[]{transition, current}) {
            var result = reports.get(admin, month.toString(), null);
            assertThat(result.peakSource()).isEqualTo("SOUNDCONNECT");
            assertThat(result.peakCoverageStart()).isEqualTo(cutover);
            assertThat(result.peaks().members().count()).isEqualTo(2);
        }
    }

    @Test void beforeTheFirstTickTheCurrentMonthDoesNotFallBackToAnOldPeak() {
        Instant oldStart = transition.atDay(1).atStartOfDay(AdminAnalyticsReports.ZONE).toInstant();
        when(history.started()).thenReturn(oldStart);
        when(history.peaks(any(), any(), eq("ALL"), eq(true))).thenReturn(new AnalyticsReport.Peaks(null, null, 0));
        when(history.peaks(any(), any(), eq("ALL"), eq(false))).thenReturn(
                new AnalyticsReport.Peaks(new AnalyticsReport.Peak(15, oldStart), null, 10));

        var result = reports.get(admin, current.toString(), null);
        assertThat(result.live()).isNull();
        assertThat(result.issues()).contains("LIVE_UNAVAILABLE");
        assertThat(result.peakSource()).isEqualTo("SOUNDCONNECT");
        assertThat(result.peakCoverageStart()).isNull();
        assertThat(result.peaks()).isEqualTo(new AnalyticsReport.Peaks(null, null, 0));
    }
}
