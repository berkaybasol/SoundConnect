-- Explicit forward migration. This does not enable a notification type or change account admission.
BEGIN;
SET LOCAL lock_timeout='10s';
SET LOCAL statement_timeout='60s';
ALTER TABLE tbl_venue_applications ADD COLUMN IF NOT EXISTS approved_venue_id uuid;
CREATE INDEX IF NOT EXISTS idx_venue_application_applicant_latest
    ON tbl_venue_applications(user_id, application_date DESC, created_at DESC, id DESC);
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='tbl_venue_applications'::regclass
            AND conname='fk_venue_application_approved_venue') THEN
        ALTER TABLE tbl_venue_applications ADD CONSTRAINT fk_venue_application_approved_venue
            FOREIGN KEY (approved_venue_id) REFERENCES tbl_venues(id) ON DELETE SET NULL;
    END IF;
END $$;
ALTER TABLE tbl_push_device ADD COLUMN IF NOT EXISTS application_scope_id uuid;
-- Scope is a durable security restriction, intentionally not a cascading FK: deleting
-- an application must never turn its restricted device into an ordinary active device.
ALTER TABLE tbl_push_device DROP CONSTRAINT IF EXISTS ck_push_device_presentation;
ALTER TABLE tbl_push_device ADD CONSTRAINT ck_push_device_presentation
    CHECK (presentation_version IS NULL OR (platform='ANDROID'
        AND presentation_version IN ('ANDROID_DM_V1','ANDROID_NATIVE_V2','ANDROID_NATIVE_V3')));
ALTER TABLE tbl_push_device DROP CONSTRAINT IF EXISTS ck_push_device_application_scope;
ALTER TABLE tbl_push_device ADD CONSTRAINT ck_push_device_application_scope
    CHECK (application_scope_id IS NULL OR (platform='ANDROID'
        AND (revoked_at IS NOT NULL OR (presentation_version IS NOT NULL AND presentation_version='ANDROID_NATIVE_V3'))));
-- Preserve historical enum checks, including prior OR extensions. Never relax
-- compound business rules or functions/operators outside direct enum equality.
DO $enum$
DECLARE item record; tokens text;
BEGIN
    FOR item IN
        SELECT c.conname,c.convalidated,pg_get_expr(c.conbin,c.conrelid) AS expression
        FROM pg_constraint c JOIN pg_attribute a ON a.attrelid=c.conrelid AND a.attname='type'
        WHERE c.conrelid='tbl_notification'::regclass AND c.contype='c'
          AND c.conkey=ARRAY[a.attnum]::smallint[]
          AND position('VENUE_APPLICATION_APPROVED' in pg_get_expr(c.conbin,c.conrelid))=0
    LOOP
        tokens := regexp_replace(item.expression, '''[A-Z][A-Z0-9_]*''', '', 'g');
        tokens := regexp_replace(tokens, '\m(type|text|character|varying|varchar|ANY|ARRAY|OR)\M', '', 'gi');
        IF translate(tokens, '()[],:= ' || chr(9) || chr(10) || chr(13), '') = '' THEN
            EXECUTE format('ALTER TABLE tbl_notification DROP CONSTRAINT %I',item.conname);
            EXECUTE format('ALTER TABLE tbl_notification ADD CONSTRAINT %I CHECK ((%s) OR type=''VENUE_APPLICATION_APPROVED'') NOT VALID',item.conname,item.expression);
            IF item.convalidated THEN EXECUTE format('ALTER TABLE tbl_notification VALIDATE CONSTRAINT %I',item.conname); END IF;
        END IF;
    END LOOP;
END $enum$;
INSERT INTO soundconnect_schema_migrations(migration_id)
VALUES ('2026-09-24-venue-application-notifications') ON CONFLICT DO NOTHING;
COMMIT;
