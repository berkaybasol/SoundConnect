package com.berkayb.soundconnect.modules.notification.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Explicit operator migration, never DDL against an application database at startup. */
@Component
@RequiredArgsConstructor
public class BandNotificationIdentitySchema implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    @Override public void run(ApplicationArguments args) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from soundconnect_schema_migrations
                    where migration_id='2026-09-29-band-notification-identity')
                  and exists(select 1 from pg_constraint where conrelid='tbl_notification'::regclass
                    and conname='ck_band_notification_identity' and contype='c' and convalidated
                    and pg_get_constraintdef(oid) like '%soundconnect_band_notification_payload%')
                  and exists(select 1 from pg_trigger where tgrelid='tbl_notification'::regclass
                    and tgname='trg_band_notification_identity' and tgenabled='O' and not tgisinternal
                    and tgfoid=to_regprocedure('soundconnect_sanitize_band_notification()'))
                """,Boolean.class))) throw new IllegalStateException(
                "Apply 2026-09-29-band-notification-identity.sql before this binary");
    }
}
