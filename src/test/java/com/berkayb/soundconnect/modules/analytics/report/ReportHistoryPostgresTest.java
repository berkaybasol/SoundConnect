package com.berkayb.soundconnect.modules.analytics.report;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.service.MemberAnalyticsPreferenceService;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class ReportHistoryPostgresTest {
    @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16.4-alpine");
    static JdbcTemplate db;
    static TransactionTemplate tx;
    static ReportHistory history;
    static FirstWeekDuration duration;
    @BeforeAll static void setup() throws Exception {
        var ds=new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
        new ResourceDatabasePopulator(new ClassPathResource("admin-account-directory.sql")).execute(ds);
        db=new JdbcTemplate(ds);
        db.execute("alter table tbl_user add column access_suspended boolean default false");
        try(var conn=ds.getConnection();var stmt=conn.createStatement()) {
            for(var file: new String[]{"2026-11-26-member-analytics-preference.sql","2026-11-26-analytics-reports.sql",
                    "2026-11-26-analytics-live-presence.sql"})
                stmt.execute(Files.readString(Path.of("scripts/db",file)));
        }
        tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        var named=new NamedParameterJdbcTemplate(ds);
        history=new ReportHistory(named);
        duration=new FirstWeekDuration(named);
    }
    @Test void thresholdIsStrictAndTwoDevicesCannotDoubleCountAnOverlappingWindow() {
        var member=UUID.randomUUID(); var identity=UUID.randomUUID();
        var actor=UserDetailsImpl.fromUser(User.builder().id(member).sessionVersion(7).build());
        var base=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).minusSeconds(60);
        tx.executeWithoutResult(s->{
            db.update("insert into tbl_user(id,user_name,public_code,status,email_verified,session_version) values(?,?,?,'ACTIVE',true,7)",
                    member,"test_duration","sc-duration");
            db.update("insert into user_roles values(?,2)",member);
        });
        db.update("update tbl_analytics_account_opening set opened_at=now()-interval '1 day' where user_id=?",member);
        db.update("insert into tbl_member_analytics_preference values(?,'GRANTED',?,1,now()-interval '2 hours',?)",
                member,MemberAnalyticsPreferenceService.POLICY_VERSION,identity);
        db.update("update tbl_analytics_week_cohort set duration_ms=450000 where user_id=?",member);
        var sample=new FirstWeekDuration.Sample(identity,UUID.randomUUID(),base,30000L);
        assertThat(tx.execute(s->duration.add(actor,sample)).windowOpen()).isTrue();
        assertThat(tx.execute(s->duration.add(actor,sample)).windowOpen()).isTrue();
        var duplicate=new FirstWeekDuration.Sample(identity,UUID.randomUUID(),base.plusSeconds(10),10000L);
        assertThat(tx.execute(s->duration.add(actor,duplicate)).windowOpen()).isTrue();
        assertThat(db.queryForObject("select duration_ms from tbl_analytics_week_cohort where user_id=?",Integer.class,member)).isEqualTo(480000);
        var partial=new FirstWeekDuration.Sample(identity,UUID.randomUUID(),base.plusSeconds(25),10000L);
        assertThat(tx.execute(s->duration.add(actor,partial)).windowOpen()).isFalse();
        db.update("update tbl_analytics_week_cohort set opened_at=now()-interval '8 days' where user_id=?",member);
        Instant end=Instant.now().plusSeconds(60), start=end.minus(Duration.ofDays(10));
        var cohort=history.firstWeek(start,end,"LISTENER",end);
        assertThat(cohort.eligible()).isOne(); assertThat(cohort.overEightMinutes()).isOne();
        var before=history.membership(start,end,"LISTENER",false);
        assertThat(before.newAccounts()).isOne(); assertThat(before.change()).isNull();
        db.update("update tbl_user set erased_at=clock_timestamp() where id=?",member);
        var after=history.membership(start,end,"LISTENER",false);
        assertThat(after.newAccounts()).isOne(); assertThat(after.totalAccounts()).isZero(); assertThat(after.deletedAccounts()).isOne();
        assertThat(history.firstWeek(start,end,"LISTENER",end).overEightMinutes()).isOne();
        assertThat(tx.execute(s->duration.add(actor,partial)).windowOpen()).isFalse();
    }
    @Test void closedWindowsCountAnIdentityOnceAndKeepSourceSpecificPeaksAcrossIstanbulMidnight() {
        Instant at = Instant.parse("2026-12-31T20:59:00Z");
        Instant midnight = at.plusSeconds(60);
        UUID musician = UUID.randomUUID();
        UUID listener = UUID.randomUUID();
        UUID venue = UUID.randomUUID();
        UUID guest = UUID.randomUUID();
        tx.executeWithoutResult(status -> {
            status.setRollbackOnly();
            db.update("insert into tbl_analytics_live_start values (true,?)", Timestamp.from(at.minusSeconds(360)));
            db.update("""
                    insert into tbl_analytics_live_tick
                    select generate_series(?::timestamptz,?::timestamptz,interval '1 minute')
                    """,Timestamp.from(at.minusSeconds(300)),Timestamp.from(at.minusSeconds(60)));
            db.update("insert into tbl_analytics_month_peak values ('2026-12-01','ALL',15,?,19)",Timestamp.from(at));
            presence(at.minusSeconds(360), "STUDIO", UUID.randomUUID());
            presence(at.minusSeconds(300), "MUSICIAN", musician);
            presence(at.minusSeconds(300), "MUSICIAN", musician);
            presence(at.minusSeconds(240), "MUSICIAN", musician);
            presence(at.minusSeconds(300), "LISTENER", listener);
            presence(at.minusSeconds(300), "GUEST", guest);
            presence(at, "VENUE", venue);
            history.captureLive(at);
            history.captureLive(at);

            assertThat(history.live("ALL",at)).isEqualTo(new AnalyticsReport.Live(at, 2, 1));
            assertThat(history.live("MUSICIAN",at)).isEqualTo(new AnalyticsReport.Live(at, 1, 1));
            assertThat(history.live("VENUE",at).members()).isZero();
            assertThat(history.live("STUDIO",at).members()).isZero();
            assertThat(history.peaks(at,midnight,"ALL",true).samples()).isOne();
            assertThat(history.peaks(at,midnight,"ALL",true).members().count()).isEqualTo(2);
            assertThat(history.peaks(at,midnight,"ALL",false).members().count()).isEqualTo(15);

            history.captureLive(midnight);
            assertThat(history.live("ALL",midnight)).isEqualTo(new AnalyticsReport.Live(midnight, 2, 0));
            assertThat(history.live("LISTENER",midnight).members()).isZero();
            assertThat(history.live("VENUE",midnight).members()).isOne();
            assertThat(history.peaks(midnight,midnight.plusSeconds(1),"ALL",true).samples()).isOne();
            assertThat(history.peaks(at,midnight,"ALL",true).guests().count()).isOne();

            presence(midnight, "STUDIO", UUID.randomUUID());
            presence(midnight, "LISTENER", UUID.randomUUID());
            history.captureLive(midnight.plusSeconds(60));
            presence(midnight.plusSeconds(60), "GUEST", UUID.randomUUID());
            presence(midnight.plusSeconds(60), "GUEST", UUID.randomUUID());
            history.captureLive(midnight.plusSeconds(120));
            var january = history.peaks(midnight,midnight.plusSeconds(121),"ALL",true);
            assertThat(january.members()).isEqualTo(new AnalyticsReport.Peak(3, midnight.plusSeconds(60)));
            assertThat(january.guests()).isEqualTo(new AnalyticsReport.Peak(2, midnight.plusSeconds(120)));
            // A gap in scheduler ticks must not create a measured zero or a new peak sample.
            history.captureLive(midnight.plusSeconds(300));
            assertThat(history.live("ALL",midnight.plusSeconds(300))).isNull();
            assertThat(history.peaks(midnight,midnight.plusSeconds(301),"ALL",true).samples()).isEqualTo(3);
        });
    }

    @Test void twoDevicesRenewedConsentAndRoleChangesKeepOneLiveMember() {
        var named = new NamedParameterJdbcTemplate(db);
        var activity = new LiveActivityStore(named);
        var preferences = new MemberAnalyticsPreferenceService(named);
        var member = UUID.randomUUID();
        var guest = UUID.randomUUID();
        var actor = UserDetailsImpl.fromUser(User.builder().id(member).sessionVersion(7).build());
        var staleSession = UserDetailsImpl.fromUser(User.builder().id(member).sessionVersion(6).build());
        tx.executeWithoutResult(status -> {
            status.setRollbackOnly();
            db.execute("set constraints all immediate");
            db.update("insert into tbl_user(id,user_name,public_code,status,email_verified,session_version) values(?,?,?,'ACTIVE',true,7)",
                    member, "live_test_member", "sc-live-test");
            db.update("insert into user_roles values (?,3)", member);
            activity.member(actor, UUID.randomUUID());
            var oldGrant = preferences.change(actor, choice(MemberAnalyticsPreferenceService.Decision.GRANTED, 0)).analyticsUserId();
            activity.member(staleSession, oldGrant);
            assertThat(db.queryForObject("select count(*) from tbl_analytics_live_activity", Integer.class)).isZero();
            activity.member(actor, oldGrant);
            activity.member(actor, oldGrant);
            // Put the first device's observation in a different minute, still within the same reporting window.
            db.update("update tbl_analytics_live_activity set minute_at=minute_at-interval '2 minutes'");
            preferences.change(actor, choice(MemberAnalyticsPreferenceService.Decision.DENIED, 1));
            activity.member(actor, oldGrant);
            var newGrant = preferences.change(actor, choice(MemberAnalyticsPreferenceService.Decision.GRANTED, 2)).analyticsUserId();
            assertThat(newGrant).isNotEqualTo(oldGrant);
            activity.member(actor, oldGrant);
            assertThat(db.queryForObject("select count(*) from tbl_analytics_live_activity", Integer.class)).isOne();
            activity.member(actor, newGrant);
            db.update("update user_roles set role_id=4 where user_id=?", member);
            activity.member(actor, newGrant);
            activity.guest(guest);
            activity.guest(guest);
            var actors = db.queryForList("select distinct actor_id from tbl_analytics_live_activity where audience<>'GUEST'", UUID.class);
            assertThat(actors).hasSize(1).doesNotContain(member, oldGrant, newGrant);
            db.update("insert into user_roles values (?,1)", member);
            db.update("delete from tbl_analytics_live_activity where audience='VENUE'");
            activity.member(actor, newGrant);
            assertThat(db.queryForObject("select count(*) from tbl_analytics_live_activity where audience='VENUE'", Integer.class)).isZero();
            db.update("delete from user_roles where user_id=? and role_id=1", member);
            activity.member(actor, newGrant);

            Instant at = db.queryForObject("select max(minute_at) from tbl_analytics_live_activity", Timestamp.class)
                    .toInstant().plusSeconds(60);
            db.update("insert into tbl_analytics_live_start values(true,?) on conflict(singleton) do update set started_at=excluded.started_at",
                    Timestamp.from(at.minusSeconds(360)));
            db.update("insert into tbl_analytics_live_tick select generate_series(?::timestamptz,?::timestamptz,interval '1 minute')",
                    Timestamp.from(at.minusSeconds(300)),Timestamp.from(at.minusSeconds(60)));
            history.captureLive(at);
            var live = history.live("ALL", at);
            assertThat(live).isEqualTo(new AnalyticsReport.Live(at, 1, 1));
            assertThat(history.live("MUSICIAN", at).members()).isOne();
            assertThat(history.live("VENUE", at).members()).isOne();
            assertThat(live.getClass().getRecordComponents()).extracting(java.lang.reflect.RecordComponent::getName)
                    .containsExactly("asOf", "members", "guests");
        });
    }

    private MemberAnalyticsPreferenceService.Change choice(MemberAnalyticsPreferenceService.Decision decision, long revision) {
        return new MemberAnalyticsPreferenceService.Change(decision, MemberAnalyticsPreferenceService.POLICY_VERSION, revision);
    }

    private void presence(Instant minute, String audience, UUID actor) {
        db.update("insert into tbl_analytics_live_activity values (?,?,?) on conflict do nothing",
                Timestamp.from(minute), audience, actor);
    }

    @Test void rawVisitsExpireWhenSamplingHasStoppedWithoutChangingThePeaks() {
        tx.executeWithoutResult(status -> {
            status.setRollbackOnly();
            Instant now = db.queryForObject("select date_trunc('minute',now())",Timestamp.class).toInstant();
            Instant expired = now.minusSeconds(600);
            Instant recent = now.minusSeconds(120);
            for (Instant at : new Instant[]{expired,recent}) {
                presence(at,"GUEST",UUID.randomUUID());
                db.update("insert into tbl_analytics_live_tick values (?)",Timestamp.from(at));
            }
            history.expireLiveVisits();
            assertThat(db.queryForList("select minute_at from tbl_analytics_live_activity",Timestamp.class))
                    .containsExactly(Timestamp.from(recent));
            assertThat(db.queryForList("select minute_at from tbl_analytics_live_tick",Timestamp.class))
                    .containsExactly(Timestamp.from(recent));
            assertThat(history.liveStarted()).isNull();
            assertThat(history.peaks(expired,now,"ALL",true).samples()).isZero();
        });
    }

    @Test void novemberCohortsEnterTheirOwnMonthAfterExactly168Hours() {
        Instant november = Instant.parse("2026-10-31T21:00:00Z");
        Instant december = Instant.parse("2026-11-30T21:00:00Z");
        Instant opened = Instant.parse("2026-11-26T04:00:15.831608Z");
        Instant mature = opened.plus(Duration.ofDays(7));
        tx.executeWithoutResult(status -> {
            status.setRollbackOnly();
            db.update("delete from tbl_analytics_week_cohort");
            addCohort(opened, "LISTENER", 358860, "table_join_requested");
            addCohort(opened, "MUSICIAN", 150002, "venue_musician_connected");
            addCohort(opened, "VENUE", 30002, "venue_musician_connected");
            addCohort(opened, "STUDIO", 480001, "studio_room_created");

            assertThat(history.firstWeek(november, december, "ALL", mature.minusNanos(1000)).eligible()).isZero();
            assertThat(history.firstWeek(november, december, "ALL", mature))
                    .isEqualTo(new AnalyticsReport.FirstWeek(4, 4, 1, Map.of(
                            "table_join_requested", 1L, "venue_musician_connected", 2L, "studio_room_created", 1L)));
            assertThat(history.firstWeek(november, december, "STUDIO", mature))
                    .isEqualTo(new AnalyticsReport.FirstWeek(1, 1, 1, Map.of("studio_room_created", 1L)));
            assertThat(history.firstWeek(november, december, "MUSICIAN", mature))
                    .isEqualTo(new AnalyticsReport.FirstWeek(1, 1, 0, Map.of("venue_musician_connected", 1L)));
            assertThat(history.firstWeek(november, december, "VENUE", mature))
                    .isEqualTo(new AnalyticsReport.FirstWeek(1, 1, 0, Map.of("venue_musician_connected", 1L)));
            assertThat(history.firstWeek(november, december, "LISTENER", mature))
                    .isEqualTo(new AnalyticsReport.FirstWeek(1, 1, 0, Map.of("table_join_requested", 1L)));

            addCohort(november.minusNanos(1000), "LISTENER", 480001, "table_join_requested");
            addCohort(november, "LISTENER", 480000, null);
            addCohort(december.minusNanos(1000), "LISTENER", 480001, null);
            addCohort(december, "LISTENER", 480001, "table_join_requested");
            Instant afterBothMonths = december.plus(Duration.ofDays(8));
            assertThat(history.firstWeek(november, december, "LISTENER", afterBothMonths))
                    .isEqualTo(new AnalyticsReport.FirstWeek(3, 1, 1, Map.of("table_join_requested", 1L)));
        });
    }

    private void addCohort(Instant opened, String role, int durationMs, String action) {
        db.update("insert into tbl_analytics_week_cohort(opened_at,profile_type,duration_ms,first_action) values (?,?,?,?)",
                Timestamp.from(opened), role, durationMs, action);
    }
}
