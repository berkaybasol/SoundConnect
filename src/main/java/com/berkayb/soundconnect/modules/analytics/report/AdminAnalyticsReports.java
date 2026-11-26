package com.berkayb.soundconnect.modules.analytics.report;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.admin.security.AdminPanelReadAccess;
import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileType;
import com.berkayb.soundconnect.modules.role.enums.PermissionEnum;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminAnalyticsReports {
    static final ZoneId ZONE=ZoneId.of("Europe/Istanbul");
    private final AdminPanelReadAccess access;
    private final ReportHistory history;
    private final Ga4Reports google;
    private record Key(LocalDate start, LocalDate end, String profile, boolean previous) {}
    private record Cached(Instant until, AnalyticsReport.Usage usage) {}
    private final Map<Key,Cached> cache=new LinkedHashMap<>(32,0.75f,true);

    public AnalyticsReport get(UserDetailsImpl actor,String month,ProfileType profile) {
        access.require(actor,PermissionEnum.READ_USERS);
        Instant now=Instant.now();
        LocalDate today=now.atZone(ZONE).toLocalDate();
        YearMonth period;
        try { period=YearMonth.parse(month); }
        catch(DateTimeParseException | NullPointerException ex) { throw new SoundConnectException(ErrorType.VALIDATION_ERROR); }
        String role=profile==null?"ALL":profile.name();
        if (period.getYear()<2000 || period.isAfter(YearMonth.from(today)) || (profile!=null && !ReportHistory.ROLES.contains(role)))
            throw new SoundConnectException(ErrorType.VALIDATION_ERROR);
        Instant first=history.started();
        LocalDate coverage=first.atZone(ZONE).toLocalDate();
        Instant start=period.atDay(1).atStartOfDay(ZONE).toInstant();
        var next=period.plusMonths(1).atDay(1).atStartOfDay(ZONE).toInstant();
        Instant end=now.isBefore(next)?now:next;
        var issues=new ArrayList<String>();
        Instant cutover=history.liveStarted();
        boolean firstPartyPeaks=cutover==null ? period.equals(YearMonth.from(today)) : next.isAfter(cutover);
        String peakSource=firstPartyPeaks?"SOUNDCONNECT":"GA4";
        Instant peakCoverageStart=firstPartyPeaks?cutover:first;
        var live=history.live(role,now);
        if(live==null) issues.add("LIVE_UNAVAILABLE");
        if(!end.isAfter(first)) {
            issues.add("BEFORE_COVERAGE");
            return new AnalyticsReport(month,role,now,coverage,live,null,new AnalyticsReport.Peaks(null,null,0),null,null,issues,
                    "SOUNDCONNECT",peakSource,peakCoverageStart);
        }
        if(start.isBefore(first)) issues.add("PARTIAL_MONTH");
        var membership=history.membership(start,end,role,!first.isAfter(start));
        var peaks=history.peaks(start,end,role,firstPartyPeaks);
        var week=history.firstWeek(start,end,role,now);
        LocalDate reportStart=start.isBefore(first)?coverage:period.atDay(1);
        LocalDate reportEnd=period.equals(YearMonth.from(today))?today:period.atEndOfMonth();
        boolean previous=!first.isAfter(period.minusMonths(1).atDay(1).atStartOfDay(ZONE).toInstant());
        var usage=monthly(new Key(reportStart,reportEnd,role,previous),now);
        if(usage==null) issues.add("GA4_UNAVAILABLE");
        return new AnalyticsReport(month,role,now,coverage,live,membership,peaks,usage,week,List.copyOf(issues),
                "SOUNDCONNECT",peakSource,peakCoverageStart);
    }
    private synchronized AnalyticsReport.Usage monthly(Key key,Instant at) {
        Cached entry=cache.get(key);
        if(entry!=null && at.isBefore(entry.until())) return entry.usage();
        AnalyticsReport.Usage value=null;
        if(google.enabled()) {
            try { value=google.monthly(key.start(),key.end(),key.profile(),key.previous()); }
            catch(Ga4Reports.Unavailable ex) { log.warn("GA4 monthly report unavailable"); }
        }
        cache.put(key,new Cached(at.plusSeconds(value==null?60:900),value));
        if(cache.size()>32) cache.remove(cache.keySet().iterator().next());
        return value;
    }
}
