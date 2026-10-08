package com.berkayb.soundconnect.modules.notification.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Refuse a new binary while legacy identity snapshots remain unredacted. No DDL at startup. */
@Component
@RequiredArgsConstructor
public class MediaNotificationIdentitySchema implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    @Override public void run(ApplicationArguments args) {
        try {
            if (Boolean.TRUE.equals(jdbc.queryForObject("""
                    select exists(select 1 from soundconnect_schema_migrations
                        where migration_id='2026-09-28-media-notification-identity')
                    and exists(select 1 from pg_constraint where conrelid='tbl_notification'::regclass
                        and conname='ck_media_notification_identity' and contype='c' and convalidated
                        and pg_get_constraintdef(oid) like '%soundconnect_media_notification_payload%')
                    and exists(select 1 from pg_trigger where tgrelid='tbl_notification'::regclass
                        and tgname='trg_media_notification_identity' and tgenabled='O' and not tgisinternal
                        and tgfoid=to_regprocedure('soundconnect_sanitize_media_notification()'))
                    """, Boolean.class))) return;
        } catch (RuntimeException unavailable) {
            throw new IllegalStateException("Apply 2026-09-28-media-notification-identity.sql before this binary", unavailable);
        }
        throw new IllegalStateException("MEDIA notification identity migration/constraint/trigger is missing or invalid");
    }
}
