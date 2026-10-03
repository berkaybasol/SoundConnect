-- Forward-only V10 capability. No enrollment, backfill, retention or rollout writes.
BEGIN;
SET LOCAL lock_timeout='10s';
SET LOCAL statement_timeout='60s';
DO $$ BEGIN
    IF NOT EXISTS(SELECT 1 FROM soundconnect_schema_migrations
        WHERE migration_id='2026-10-01-push-native-collab-capability') THEN
        RAISE EXCEPTION 'Overthinking capability requires the V9 migration chain';
    END IF;
    IF NOT EXISTS(SELECT 1 FROM pg_constraint WHERE conrelid='tbl_push_device'::regclass
        AND conname='ck_push_device_presentation' AND contype='c' AND convalidated
        AND pg_get_constraintdef(oid) LIKE '%ANDROID_NATIVE_V9%')
        OR NOT EXISTS(SELECT 1 FROM pg_constraint WHERE conrelid='tbl_push_device'::regclass
        AND conname='ck_push_device_application_scope' AND contype='c' AND convalidated
        AND pg_get_constraintdef(oid) LIKE '%presentation_version IS NOT NULL%'
        AND pg_get_constraintdef(oid) LIKE '%ANDROID_NATIVE_V9%') THEN
        RAISE EXCEPTION 'Overthinking capability requires validated V9 presentation and scope constraints';
    END IF;
END $$;
ALTER TABLE tbl_push_device DROP CONSTRAINT ck_push_device_presentation;
ALTER TABLE tbl_push_device ADD CONSTRAINT ck_push_device_presentation
    CHECK (presentation_version IS NULL OR (platform='ANDROID'
        AND presentation_version IN ('ANDROID_DM_V1','ANDROID_NATIVE_V2','ANDROID_NATIVE_V3','ANDROID_NATIVE_V4','ANDROID_NATIVE_V5','ANDROID_NATIVE_V6','ANDROID_NATIVE_V7','ANDROID_NATIVE_V8','ANDROID_NATIVE_V9','ANDROID_NATIVE_V10')));
ALTER TABLE tbl_push_device DROP CONSTRAINT ck_push_device_application_scope;
ALTER TABLE tbl_push_device ADD CONSTRAINT ck_push_device_application_scope
    CHECK (application_scope_id IS NULL OR (platform='ANDROID'
        AND (revoked_at IS NOT NULL OR (presentation_version IS NOT NULL
            AND presentation_version IN ('ANDROID_NATIVE_V3','ANDROID_NATIVE_V4','ANDROID_NATIVE_V5','ANDROID_NATIVE_V6','ANDROID_NATIVE_V7','ANDROID_NATIVE_V8','ANDROID_NATIVE_V9','ANDROID_NATIVE_V10')))));
INSERT INTO soundconnect_schema_migrations(migration_id)
VALUES ('2026-10-01-push-native-overthinking-capability') ON CONFLICT DO NOTHING;
COMMIT;
