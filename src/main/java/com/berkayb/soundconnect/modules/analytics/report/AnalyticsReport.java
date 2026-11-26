package com.berkayb.soundconnect.modules.analytics.report;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record AnalyticsReport(String month, String profileType, Instant asOf, LocalDate coverageStart,
                             Live live, Membership membership, Peaks peaks, Usage usage, FirstWeek firstWeek,
                             List<String> issues, String liveSource, String peakSource, Instant peakCoverageStart) {
    public record Live(Instant asOf, int members, int guests) {}
    public record Peak(int count, Instant at) {}
    public record Peaks(Peak members, Peak guests, int samples) {}
    public record Membership(long newAccounts, long deletedAccounts, long totalAccounts, Long change, long pending,
                             Map<String,Long> byRole, Map<String,Long> pendingByRole) {}
    public record FirstWeek(long eligible, long acted, long overEightMinutes, Map<String,Long> actions) {}
    public record Day(LocalDate day, long users) {}
    public record Hour(int hour, double users) {}
    public record Weekday(int weekday, double users) {}
    public record Section(String section, long visitors, long milliseconds) {}
    public record DaySection(LocalDate day, String section, long visitors, long milliseconds) {}
    public record Usage(long mau, long guestMau, double avgDau, Long previousActive, Long returned, Double returnRate,
                        long totalMilliseconds, Double minutesPerActiveDay, List<Day> daily, List<Hour> hours,
                        List<Weekday> weekdays, List<Section> sections, List<DaySection> dailySections) {}
}
