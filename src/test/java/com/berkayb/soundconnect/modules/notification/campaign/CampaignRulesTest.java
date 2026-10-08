package com.berkayb.soundconnect.modules.notification.campaign;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static com.berkayb.soundconnect.modules.notification.campaign.CampaignContract.*;

class CampaignRulesTest {
    Schedule schedule(LocalDateTime start,String zone,Repeat repeat,Integer days,Set<Integer> weekdays) {
        return CampaignRules.normalize(new Write(UUID.randomUUID(),null,"Title","Body",new Audience(Mode.ALL,Set.of(),Set.of()),
            new Target(Kind.HOME,null),new Schedule(null,zone,repeat,days,weekdays,null,10,start,null))).schedule();
    }
    @Test void localDailyKeepsWallTimeAcrossSpringGapAndFallOverlap() {
        var s=schedule(LocalDateTime.parse("2026-03-07T02:30:00"),"America/New_York",Repeat.DAILY,null,Set.of());
        assertThat(CampaignRules.next(s,s.startsAt())).isEqualTo(Instant.parse("2026-03-08T07:30:00Z"));
        assertThat(CampaignRules.next(s,Instant.parse("2026-03-08T07:30:00Z"))).isEqualTo(Instant.parse("2026-03-09T06:30:00Z"));
        var gapStart=schedule(LocalDateTime.parse("2026-03-08T02:30:00"),"America/New_York",Repeat.DAILY,null,Set.of());
        assertThat(gapStart.startsAt()).isEqualTo(Instant.parse("2026-03-08T07:30:00Z"));
        assertThat(CampaignRules.next(gapStart,gapStart.startsAt())).isEqualTo(Instant.parse("2026-03-09T06:30:00Z"));
        var fall=schedule(LocalDateTime.parse("2026-10-31T01:30:00"),"America/New_York",Repeat.DAILY,null,Set.of());
        assertThat(CampaignRules.next(fall,fall.startsAt())).isEqualTo(Instant.parse("2026-11-01T05:30:00Z"));
        assertThat(CampaignRules.next(fall,Instant.parse("2026-11-01T05:30:00Z"))).isEqualTo(Instant.parse("2026-11-02T06:30:00Z"));
    }
    @Test void intervalSkipsYearsInConstantWorkAndWeeklyUsesIsoDays() {
        var s=schedule(LocalDateTime.parse("2020-01-01T09:00:00"),"Europe/Istanbul",Repeat.INTERVAL,3,Set.of());
        Instant now=Instant.parse("2026-10-07T08:00:00Z");var next=CampaignRules.next(s,now);
        assertThat(next).isAfter(now).isBefore(now.plus(Duration.ofDays(4)));
        var weekly=schedule(LocalDateTime.parse("2026-10-07T09:00:00"),"Europe/Istanbul",Repeat.WEEKLY,null,Set.of(1,5));
        assertThat(CampaignRules.next(weekly,weekly.startsAt().minusSeconds(1))).isEqualTo(Instant.parse("2026-10-09T06:00:00Z"));
    }
    @Test void normalizationNeverSilentlyAcceptsInvisibleTextOrOversizeUtf16() {
        assertThat(CampaignRules.text(" Hi\r\nthere\t! ",120)).isEqualTo("Hi there !");
        assertThat(CampaignRules.text("\u00A0Hello\u202F",120)).isEqualTo("Hello");
        for(String bad:List.of(" ","\u00A0\u202F","x\u202Ey","x\u200By","x\u2028y","x\u0000y","x\uD800y","😀".repeat(61)))
            assertThatThrownBy(()->CampaignRules.text(bad,120)).isInstanceOf(RuntimeException.class);
        assertThat(CampaignRules.text("😀".repeat(60),120)).hasSize(120);
    }
    @Test void onceAndEndBoundaryAreExclusiveAndMixedAudienceIsRejected() {
        var once=schedule(LocalDateTime.parse("2026-10-07T09:00:00"),"Europe/Istanbul",Repeat.ONCE,null,Set.of());
        assertThat(CampaignRules.next(once,once.startsAt())).isNull();
        assertThatThrownBy(()->CampaignRules.normalize(new Write(UUID.randomUUID(),null,"a","b",
            new Audience(Mode.ALL,Set.of("LISTENER"),Set.of()),new Target(Kind.HOME,null),once))).isInstanceOf(RuntimeException.class);
    }
}
