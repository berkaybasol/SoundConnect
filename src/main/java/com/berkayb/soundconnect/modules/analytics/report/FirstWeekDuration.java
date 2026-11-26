package com.berkayb.soundconnect.modules.analytics.report;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.user.service.MemberAnalyticsPreferenceService;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FirstWeekDuration {
    public record Sample(UUID analyticsUserId, UUID sampleId, Instant startedAt, Long durationMs) {}
    public record Receipt(boolean windowOpen) {}
    private final NamedParameterJdbcTemplate jdbc;

    @Transactional
    public Receipt add(UserDetailsImpl actor, Sample sample) {
        if (actor == null || actor.getId() == null) throw new SoundConnectException(ErrorType.UNAUTHORIZED);
        if (sample == null || sample.analyticsUserId() == null || sample.sampleId() == null || sample.startedAt() == null
                || sample.durationMs() == null || sample.durationMs() < 1 || sample.durationMs() > 120000) {
            throw new SoundConnectException(ErrorType.VALIDATION_ERROR);
        }
        var params = new MapSqlParameterSource("user", actor.getId()).addValue("session",actor.getUser().getSessionVersion())
                .addValue("identity",sample.analyticsUserId()).addValue("id",sample.sampleId())
                .addValue("version",MemberAnalyticsPreferenceService.POLICY_VERSION)
                .addValue("start",Timestamp.from(sample.startedAt())).addValue("ms",sample.durationMs());
        // Lock in the same order as consent withdrawal. Network retries reuse the sample UUID.
        var allowed = jdbc.queryForList("""
                select u.id from tbl_user u join tbl_member_analytics_preference p on p.user_id=u.id
                where u.id=:user and u.session_version=:session and u.status='ACTIVE' and u.erased_at is null
                  and u.email_verified and not u.access_suspended and p.decision='GRANTED'
                  and p.policy_version=:version and p.analytics_user_id=:identity for update of u,p
                """,params,UUID.class);
        if (allowed.isEmpty()) return new Receipt(false);
        var windows = jdbc.queryForList("""
                select w.opened_at from tbl_analytics_week_cohort w
                join tbl_analytics_membership_period m on m.user_id=w.user_id and m.state='ACTIVE'
                  and m.profile_type=w.profile_type
                where w.user_id=:user and w.duration_ms<=480000
                  and now()<w.opened_at+interval '168 hours'+interval '2 minutes' for update of w
                """,params,Timestamp.class);
        if (windows.isEmpty()) return new Receipt(false);
        // Intersect the measured interval with the valid consent and first-week window. A future
        // or stale packet cannot extend it. A sample crossing a boundary counts only the overlap.
        jdbc.update("""
                with bounds as (
                  select w.opened_at+interval '168 hours' as expiry,
                    least(cast(:start as timestamptz)+:ms*interval '1 millisecond',
                      w.opened_at+interval '168 hours',clock_timestamp()) as until,
                    greatest(cast(:start as timestamptz),p.changed_at,w.opened_at) as since
                  from tbl_analytics_week_cohort w join tbl_member_analytics_preference p using(user_id)
                  where w.user_id=:user and cast(:start as timestamptz) between now()-interval '5 minutes' and now()+interval '5 seconds'
                ), valid as (
                  select tstzrange(since,until,'[)') as span,expiry from bounds where until>since
                ), fresh as (
                  select v.*, tstzmultirange(span) - coalesce((
                    select range_agg(s.measured) from tbl_analytics_week_sample s
                    where s.user_id=:user and s.measured && v.span
                  ), '{}'::tstzmultirange) as parts from valid v
                ), delta as (
                  select fresh.*, (select sum(floor(1000*extract(epoch from upper(r)-lower(r))))::int
                    from unnest(parts) r) as milliseconds from fresh
                ), inserted as (
                  insert into tbl_analytics_week_sample(user_id,sample_id,measured,expires_at)
                  select :user,:id,span,expiry from delta where milliseconds>0 on conflict do nothing returning 1
                )
                update tbl_analytics_week_cohort set duration_ms=least(480001,duration_ms+delta.milliseconds)
                from delta,inserted where user_id=:user
                """,params);
        return new Receipt(Boolean.TRUE.equals(jdbc.queryForObject(
                "select duration_ms<=480000 and now()<opened_at+interval '168 hours' from tbl_analytics_week_cohort where user_id=:user",
                params,Boolean.class)));
    }
}
