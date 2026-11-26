package com.berkayb.soundconnect.modules.analytics.report;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
@RequiredArgsConstructor
public class ReportHistory {
    static final List<String> ROLES = List.of("LISTENER","MUSICIAN","VENUE","STUDIO");
    private final NamedParameterJdbcTemplate jdbc;

    public Instant started() {
        return jdbc.getJdbcTemplate().queryForObject("select started_at from tbl_analytics_report_start where singleton",
                (rs,index)->rs.getTimestamp(1).toInstant());
    }
    private MapSqlParameterSource period(Instant start, Instant end, String role) {
        return new MapSqlParameterSource("start", Timestamp.from(start)).addValue("end",Timestamp.from(end)).addValue("role",role);
    }
    public AnalyticsReport.Membership membership(Instant start, Instant end, String selected, boolean previousCovered) {
        var active = new LinkedHashMap<String,Long>();
        var pending = new LinkedHashMap<String,Long>();
        ROLES.forEach(role -> { active.put(role,0L); pending.put(role,0L); });
        var totals = new long[4];
        jdbc.query("""
                select profile_type,
                  count(*) filter(where state='ACTIVE' and (ended_at is null or ended_at>=:end)) as active,
                  count(*) filter(where state='PENDING' and (ended_at is null or ended_at>=:end)) as pending,
                  count(*) filter(where new_member and started_at>=:start) as opened,
                  count(*) filter(where erased and ended_at>=:start and ended_at<:end) as removed,
                  count(*) filter(where state='ACTIVE' and started_at<:start and (ended_at is null or ended_at>=:start)) as previous
                from tbl_analytics_membership_period where started_at<:end and (ended_at is null or ended_at>=:start)
                group by profile_type
                """, period(start,end,selected), rs -> {
            String role=rs.getString("profile_type");
            active.put(role,rs.getLong("active")); pending.put(role,rs.getLong("pending"));
            if (selected.equals("ALL") || selected.equals(role)) {
                totals[0]+=rs.getLong("opened"); totals[1]+=rs.getLong("removed");
                totals[2]+=rs.getLong("active"); totals[3]+=rs.getLong("previous");
            }
        });
        long wait = selected.equals("ALL") ? pending.values().stream().mapToLong(Long::longValue).sum() : pending.get(selected);
        Long change = previousCovered ? totals[2]-totals[3] : null;
        return new AnalyticsReport.Membership(totals[0],totals[1],totals[2],change,wait,active,pending);
    }
    public AnalyticsReport.FirstWeek firstWeek(Instant start, Instant end, String role, Instant asOf) {
        var params = period(start,end,role).addValue("asOf",Timestamp.from(asOf));
        var actions = new LinkedHashMap<String,Long>();
        var counts = new long[3];
        jdbc.query("""
                select first_action,count(*) members,count(*) filter(where duration_ms>480000) qualified
                from tbl_analytics_week_cohort where opened_at>=:start and opened_at<:end
                  and opened_at+interval '168 hours'<=:asOf and (:role='ALL' or profile_type=:role)
                group by first_action
                """,params, rs->{
            long members=rs.getLong("members");
            counts[0]+=members; counts[2]+=rs.getLong("qualified");
            if (rs.getString("first_action")!=null) { counts[1]+=members; actions.put(rs.getString("first_action"),members); }
        });
        return new AnalyticsReport.FirstWeek(counts[0],counts[1],counts[2],actions);
    }
    public Instant liveStarted() {
        return jdbc.getJdbcTemplate().query("select started_at from tbl_analytics_live_start where singleton",
                (rs,i) -> rs.getTimestamp(1).toInstant()).stream().findFirst().orElse(null);
    }

    @Transactional
    public void captureLive(Instant minute) {
        Map<String,?> params = Map.of("at", Timestamp.from(minute));
        jdbc.update("""
                insert into tbl_analytics_live_start(singleton,started_at) values(true,clock_timestamp())
                on conflict do nothing
                """, params);
        jdbc.update("insert into tbl_analytics_live_tick(minute_at) values(:at) on conflict do nothing", params);
        // Only complete five-minute windows qualify. Six consecutive ticks bound the window on both sides.
        jdbc.update("""
                with cutoff as (
                    select cast(:at as timestamptz) as until, cast(:at as timestamptz) - interval '5 minutes' as since
                ), covered as (
                    select c.* from cutoff c where exists (
                        select from tbl_analytics_live_start where singleton and started_at <= c.since
                    ) and (select count(*) from tbl_analytics_live_tick where minute_at between c.since and c.until)=6
                ), audiences(name) as (
                    values ('ALL'),('GUEST'),('LISTENER'),('MUSICIAN'),('VENUE'),('STUDIO')
                ), added as (
                    insert into tbl_analytics_sc_live_sample(sampled_at,audience,users_count)
                    select c.until, a.name, count(distinct p.actor_id) from covered c cross join audiences a
                    left join tbl_analytics_live_activity p on p.minute_at >= c.since and p.minute_at < c.until
                        and (p.audience=a.name or a.name='ALL' and p.audience<>'GUEST')
                    group by c.until,a.name order by a.name
                    on conflict do nothing returning *
                )
                insert into tbl_analytics_sc_month_peak as p(month_start,audience,users_count,peak_at,samples)
                select date_trunc('month',sampled_at at time zone 'Europe/Istanbul')::date,audience,users_count,sampled_at,1
                from added order by audience
                on conflict(month_start,audience) do update set samples=p.samples+1,
                    peak_at=case when excluded.users_count>p.users_count then excluded.peak_at
                        when excluded.users_count=p.users_count then least(excluded.peak_at,p.peak_at) else p.peak_at end,
                    users_count=greatest(p.users_count,excluded.users_count)
                """, params);
    }

    // Raw visits still expire when collection is switched off or a peak cannot be written.
    @Scheduled(cron = "35 * * * * *", zone = "Europe/Istanbul")
    @Transactional
    public void expireLiveVisits() {
        jdbc.getJdbcTemplate().update("delete from tbl_analytics_live_activity where minute_at <= date_trunc('minute', now()) - interval '10 minutes'");
        jdbc.getJdbcTemplate().update("delete from tbl_analytics_live_tick where minute_at <= date_trunc('minute', now()) - interval '10 minutes'");
    }
    public AnalyticsReport.Live live(String role, Instant now) {
        return jdbc.query("""
                select s.sampled_at,s.users_count,g.users_count from tbl_analytics_sc_live_sample s
                join tbl_analytics_sc_live_sample g on g.sampled_at=s.sampled_at and g.audience='GUEST'
                where s.audience=:role and s.sampled_at>=:since order by s.sampled_at desc limit 1
                """,Map.of("role",role,"since",Timestamp.from(now.minusSeconds(150))),
                (r,i)->new AnalyticsReport.Live(r.getTimestamp(1).toInstant(),r.getInt(2),r.getInt(3)))
                .stream().findFirst().orElse(null);
    }
    public AnalyticsReport.Peaks peaks(Instant start, Instant end, String role, boolean firstParty) {
        Map<String,AnalyticsReport.Peak> peaks = new LinkedHashMap<>();
        int[] samples={0};
        jdbc.query("""
                select audience,peak_at,users_count,samples from %s where audience in (:role,'GUEST')
                and month_start=date_trunc('month',cast(:start as timestamptz) at time zone 'Europe/Istanbul')::date
                """.formatted(firstParty ? "tbl_analytics_sc_month_peak" : "tbl_analytics_month_peak"),period(start,end,role),rs->{
            peaks.put(rs.getString(1),new AnalyticsReport.Peak(rs.getInt(3),rs.getTimestamp(2).toInstant()));
            if (role.equals(rs.getString(1))) samples[0]=rs.getInt(4);
        });
        return new AnalyticsReport.Peaks(peaks.get(role),peaks.get("GUEST"),samples[0]);
    }
    @Scheduled(cron="0 17 3 * * *", zone="Europe/Istanbul")
    @Transactional
    public void detachMatureCohorts() {
        // Data remains as anonymous cohort statistics. Give in-flight final samples their two minute grace.
        jdbc.getJdbcTemplate().update("delete from tbl_analytics_week_sample where expires_at < now()-interval '1 day'");
        jdbc.getJdbcTemplate().update("delete from tbl_analytics_live_sample where sampled_at < now()-interval '2 days'");
        jdbc.getJdbcTemplate().update("delete from tbl_analytics_sc_live_sample where sampled_at < now()-interval '2 days'");
        jdbc.getJdbcTemplate().update("""
                delete from tbl_analytics_first_action a using tbl_analytics_account_opening o
                where a.user_id=o.user_id and o.opened_at < now()-interval '8 days'
                """);
        jdbc.getJdbcTemplate().update("""
                update tbl_analytics_week_cohort set user_id=null
                where user_id is not null and opened_at<now()-interval '8 days'
                """);
    }
}
