package com.berkayb.soundconnect.modules.notification.push;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

@Service
@ConditionalOnProperty(name="app.notification.push.enabled",havingValue="true")
public class PushOperations implements ApplicationRunner {
    private static final Logger log=LoggerFactory.getLogger(PushOperations.class);
    private final NamedParameterJdbcTemplate jdbc;
    private final PushProperties properties;
    private final Clock clock;
    public PushOperations(NamedParameterJdbcTemplate jdbc,PushProperties properties,@Qualifier("pushClock") Clock clock) {
        this.jdbc=jdbc; this.properties=properties; this.clock=clock;
    }
    @Override public void run(ApplicationArguments arguments) {
        try {
            Long applied=jdbc.queryForObject("""
                    select count(*) from soundconnect_schema_migrations
                    where migration_id in ('2026-09-22-push-delivery-foundation','2026-09-23-push-device-registration-revision',
                        '2026-09-24-push-native-venue-capability','2026-09-24-venue-application-notifications',
                        '2026-09-24-push-native-studio-capability','2026-09-28-push-native-follow-capability','2026-09-29-push-native-media-capability','2026-09-29-push-native-band-capability','2026-09-30-table-notification-target','2026-10-01-push-native-table-capability','2026-10-01-push-native-collab-capability','2026-10-01-push-native-overthinking-capability')
                    """,Map.of(),Long.class);
            if(applied==null || applied!=12) throw new IllegalStateException();
            Boolean capability=jdbc.queryForObject("""
                    select exists(select 1 from pg_constraint
                      where conrelid='tbl_push_device'::regclass and conname='ck_push_device_presentation'
                        and convalidated and pg_get_constraintdef(oid) like '%ANDROID_DM_V1%'
                        and pg_get_constraintdef(oid) like '%ANDROID_NATIVE_V2%'
                        and pg_get_constraintdef(oid) like '%ANDROID_NATIVE_V3%'
                        and pg_get_constraintdef(oid) like '%ANDROID_NATIVE_V4%' and pg_get_constraintdef(oid) like '%ANDROID_NATIVE_V5%' and pg_get_constraintdef(oid) like '%ANDROID_NATIVE_V6%' and pg_get_constraintdef(oid) like '%ANDROID_NATIVE_V7%' and pg_get_constraintdef(oid) like '%ANDROID_NATIVE_V8%' and pg_get_constraintdef(oid) like '%ANDROID_NATIVE_V9%' and pg_get_constraintdef(oid) like '%ANDROID_NATIVE_V10%')
                    """,Map.of(),Boolean.class);
            if(!Boolean.TRUE.equals(capability)) throw new IllegalStateException();
            Boolean scope=jdbc.queryForObject("""
                    select exists(select 1 from pg_constraint where conrelid='tbl_push_device'::regclass
                      and conname='ck_push_device_application_scope' and convalidated
                      and pg_get_constraintdef(oid) like '%application_scope_id%'
                      and pg_get_constraintdef(oid) like '%presentation_version IS NOT NULL%'
                      and pg_get_constraintdef(oid) like '%ANDROID_NATIVE_V3%'
                      and pg_get_constraintdef(oid) like '%ANDROID_NATIVE_V4%' and pg_get_constraintdef(oid) like '%ANDROID_NATIVE_V5%' and pg_get_constraintdef(oid) like '%ANDROID_NATIVE_V6%' and pg_get_constraintdef(oid) like '%ANDROID_NATIVE_V7%' and pg_get_constraintdef(oid) like '%ANDROID_NATIVE_V8%' and pg_get_constraintdef(oid) like '%ANDROID_NATIVE_V9%' and pg_get_constraintdef(oid) like '%ANDROID_NATIVE_V10%')
                    """,Map.of(),Boolean.class);
            if(!Boolean.TRUE.equals(scope)) throw new IllegalStateException();
            jdbc.getJdbcTemplate().queryForList("select approved_venue_id from tbl_venue_applications limit 0");
            jdbc.getJdbcTemplate().queryForList("select installation_id,generation,token_ciphertext,client_revision,presentation_version,application_scope_id from tbl_push_device limit 0");
            jdbc.getJdbcTemplate().queryForList("select enabled,disabled_categories from tbl_push_preference limit 0");
            jdbc.getJdbcTemplate().queryForList("select status,expires_at,lease_until,finished_at from tbl_push_delivery limit 0");
        } catch(Exception missing) {
            throw new IllegalStateException("Push requires the explicit push-delivery-foundation, push-device-registration-revision push-native-venue-capability, venue-application-notifications push-native-studio-capability push-native-follow-capability push-native-media-capability push-native-band-capability, table-notification-target push-native-table-capability push-native-collab-capability and push-native-overthinking-capability migrations before enablement");
        }
    }
    public record Summary(Map<String,Long> counts, Instant oldestPendingAt, boolean degraded) { }
    public record Job(UUID id,UUID notificationId,String status,int attempts,String lastErrorCode,
                      Instant nextAttemptAt,Instant expiresAt,Instant finishedAt) { }

    @Transactional(readOnly=true)
    public Summary summary() {
        Map<String,Long> counts=new TreeMap<>();
        jdbc.getJdbcTemplate().query("select status,count(*) as total from tbl_push_delivery group by status",
                rs->{counts.put(rs.getString("status"),rs.getLong("total"));});
        Timestamp oldest=jdbc.getJdbcTemplate().queryForObject("select min(created_at) from tbl_push_delivery where status in ('PENDING','IN_FLIGHT')",Timestamp.class);
        Instant pending=oldest==null?null:oldest.toInstant();
        return new Summary(Map.copyOf(counts),pending,counts.getOrDefault("DEAD_LETTER",0L)>0
                || (pending!=null && pending.plus(properties.getUnhealthyAge()).isBefore(clock.instant())));
    }

    @Transactional(readOnly=true)
    public List<Job> failed(int limit) {
        return jdbc.query("""
                select id,notification_id,status,attempt_count,last_error_code,next_attempt_at,expires_at,finished_at
                from tbl_push_delivery where status='DEAD_LETTER' order by finished_at desc,id limit :limit
                """,Map.of("limit",limit),(rs,index)->new Job(rs.getObject("id",UUID.class),rs.getObject("notification_id",UUID.class),
                rs.getString("status"),rs.getInt("attempt_count"),rs.getString("last_error_code"),
                rs.getTimestamp("next_attempt_at").toInstant(),rs.getTimestamp("expires_at").toInstant(),rs.getTimestamp("finished_at").toInstant()));
    }

    @Transactional
    public boolean retry(UUID id, UUID operator) {
        int changed=jdbc.update("""
                update tbl_push_delivery set status='PENDING',attempt_count=0,next_attempt_at=:now,finished_at=null,
                    last_error_code=null,updated_at=:now
                where id=:id and status='DEAD_LETTER' and expires_at>:now
                """,Map.of("now",Timestamp.from(clock.instant()),"id",id));
        if(changed>0) log.info("Push operator retry requested. deliveryId={}, operatorId={}",id,operator);
        return changed>0;
    }

    @Scheduled(fixedDelayString="${app.notification.push.cleanup-delay-ms:3600000}",initialDelayString="${app.notification.push.cleanup-initial-delay-ms:60000}")
    @Transactional
    public void cleanup() {
        var now=clock.instant();
        jdbc.update("""
                update tbl_push_delivery set status='SUPPRESSED',last_error_code='EXPIRED',finished_at=:now,updated_at=:now,
                    lease_owner=null,lease_until=null
                where expires_at<=:now and (status='PENDING' or (status='IN_FLIGHT' and lease_until<=:now))
                """,Map.of("now",Timestamp.from(now)));
        jdbc.update("delete from tbl_push_delivery where finished_at<:cutoff",Map.of("cutoff",Timestamp.from(now.minus(properties.getTerminalRetention()))));
        jdbc.update("delete from tbl_push_device where last_seen_at<:cutoff",Map.of("cutoff",Timestamp.from(now.minus(properties.getDeviceStaleAfter()))));
    }
}
