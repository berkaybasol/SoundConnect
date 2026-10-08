-- Independent administrator notifications. No promotion/announcement or enrollment writes.
BEGIN;
SET LOCAL lock_timeout='10s';
SET LOCAL statement_timeout='60s';
DO $$ BEGIN
 IF NOT EXISTS(SELECT 1 FROM soundconnect_schema_migrations WHERE migration_id='2026-10-01-push-native-overthinking-capability')
  OR NOT EXISTS(SELECT 1 FROM pg_constraint WHERE conrelid='tbl_push_device'::regclass
    AND conname='ck_push_device_presentation' AND convalidated AND pg_get_constraintdef(oid) LIKE '%ANDROID_NATIVE_V10%')
  OR NOT EXISTS(SELECT 1 FROM pg_constraint WHERE conrelid='tbl_push_device'::regclass
    AND conname='ck_push_device_application_scope' AND convalidated AND pg_get_constraintdef(oid) LIKE '%ANDROID_NATIVE_V10%') THEN
  RAISE EXCEPTION 'Notification campaigns require the validated V10 push migration chain';
 END IF;
END $$;
CREATE TABLE IF NOT EXISTS tbl_notification_campaign (
 id uuid PRIMARY KEY, request_id uuid NOT NULL, created_by uuid NOT NULL,
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0), title varchar(120) NOT NULL,
 message varchar(500) NOT NULL, definition jsonb NOT NULL,
 status varchar(20) NOT NULL CHECK(status IN ('DRAFT','SCHEDULED','PAUSED','COMPLETED','CANCELLED')),
 next_run_at timestamptz, occurrences bigint NOT NULL DEFAULT 0,
 recipients bigint NOT NULL DEFAULT 0, notifications bigint NOT NULL DEFAULT 0, skipped bigint NOT NULL DEFAULT 0,
 created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
 UNIQUE(created_by,request_id)
);
CREATE INDEX IF NOT EXISTS idx_notification_campaign_due ON tbl_notification_campaign(next_run_at,id) WHERE status='SCHEDULED';
CREATE TABLE IF NOT EXISTS tbl_notification_campaign_occurrence (
 id uuid PRIMARY KEY, campaign_id uuid NOT NULL REFERENCES tbl_notification_campaign(id),
 scheduled_at timestamptz NOT NULL, started_at timestamptz NOT NULL, completed_at timestamptz,
 audience_cutoff timestamptz NOT NULL, cursor_user_id uuid,
 status varchar(20) NOT NULL CHECK(status IN ('RUNNING','COMPLETED','CANCELLED')),
 UNIQUE(campaign_id,scheduled_at)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_notification_campaign_running ON tbl_notification_campaign_occurrence(campaign_id) WHERE status='RUNNING';
CREATE TABLE IF NOT EXISTS tbl_notification_campaign_recipient (
 occurrence_id uuid NOT NULL REFERENCES tbl_notification_campaign_occurrence(id),
 recipient_id uuid NOT NULL, event_id uuid NOT NULL UNIQUE,
 PRIMARY KEY(occurrence_id,recipient_id)
);
CREATE INDEX IF NOT EXISTS idx_notification_campaign_recipient_event ON tbl_notification_campaign_recipient(event_id,recipient_id);
-- Extend only direct enum constraints; compound security constraints are preserved.
DO $enum$
DECLARE item record; tokens text;
BEGIN
 FOR item IN SELECT c.conname,c.convalidated,pg_get_expr(c.conbin,c.conrelid) expression
  FROM pg_constraint c JOIN pg_attribute a ON a.attrelid=c.conrelid AND a.attname='type'
  WHERE c.conrelid='tbl_notification'::regclass AND c.contype='c' AND c.conkey=ARRAY[a.attnum]::smallint[]
   AND position('ADMIN_BROADCAST' in pg_get_expr(c.conbin,c.conrelid))=0
 LOOP
  tokens:=regexp_replace(item.expression,'''[A-Z][A-Z0-9_]*''','','g');
  tokens:=regexp_replace(tokens,'\m(type|text|character|varying|varchar|ANY|ARRAY|OR)\M','','gi');
  IF translate(tokens,'()[],:= '||chr(9)||chr(10)||chr(13),'')='' THEN
   EXECUTE format('ALTER TABLE tbl_notification DROP CONSTRAINT %I',item.conname);
   EXECUTE format('ALTER TABLE tbl_notification ADD CONSTRAINT %I CHECK ((%s) OR type=''ADMIN_BROADCAST'') NOT VALID',item.conname,item.expression);
   IF item.convalidated THEN EXECUTE format('ALTER TABLE tbl_notification VALIDATE CONSTRAINT %I',item.conname); END IF;
  END IF;
 END LOOP;
END $enum$;
ALTER TABLE tbl_push_device DROP CONSTRAINT IF EXISTS ck_push_device_presentation;
ALTER TABLE tbl_push_device ADD CONSTRAINT ck_push_device_presentation CHECK(presentation_version IS NULL OR
 (platform='ANDROID' AND presentation_version IN ('ANDROID_DM_V1','ANDROID_NATIVE_V2','ANDROID_NATIVE_V3','ANDROID_NATIVE_V4','ANDROID_NATIVE_V5','ANDROID_NATIVE_V6','ANDROID_NATIVE_V7','ANDROID_NATIVE_V8','ANDROID_NATIVE_V9','ANDROID_NATIVE_V10','ANDROID_NATIVE_V11')));
ALTER TABLE tbl_push_device DROP CONSTRAINT IF EXISTS ck_push_device_application_scope;
ALTER TABLE tbl_push_device ADD CONSTRAINT ck_push_device_application_scope CHECK(application_scope_id IS NULL OR
 (platform='ANDROID' AND (revoked_at IS NOT NULL OR (presentation_version IS NOT NULL AND presentation_version IN
 ('ANDROID_NATIVE_V3','ANDROID_NATIVE_V4','ANDROID_NATIVE_V5','ANDROID_NATIVE_V6','ANDROID_NATIVE_V7','ANDROID_NATIVE_V8','ANDROID_NATIVE_V9','ANDROID_NATIVE_V10','ANDROID_NATIVE_V11')))));
INSERT INTO soundconnect_schema_migrations(migration_id) VALUES('2026-10-07-notification-campaigns') ON CONFLICT DO NOTHING;
COMMIT;
