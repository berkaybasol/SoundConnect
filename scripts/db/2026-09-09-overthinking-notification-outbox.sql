-- Overthinking notifications: additive, rerunnable migration. Stop API writers before applying.
-- Apply after notification replay receipts; no domain or account FK is intentional for historical delivery.
BEGIN;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '60s';

DO $migration$
BEGIN
    IF to_regclass('tbl_notification_receipt') IS NULL THEN
        RAISE EXCEPTION 'Apply 2026-09-07-notification-replay-receipts.sql before the Overthinking notification outbox';
    END IF;
END
$migration$;

CREATE TABLE IF NOT EXISTS soundconnect_schema_migrations (
    migration_id varchar(160) PRIMARY KEY,
    applied_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS tbl_overthinking_notification_outbox (
    event_id uuid,
    recipient_id uuid,
    notification_type varchar(64),
    title varchar(160),
    message varchar(1000),
    payload jsonb,
    email_force boolean DEFAULT false,
    occurred_at timestamp with time zone,
    status varchar(24),
    attempt_count integer DEFAULT 0,
    next_attempt_at timestamp with time zone,
    lease_owner varchar(100),
    lease_until timestamp with time zone,
    last_error_type varchar(200),
    published_at timestamp with time zone,
    created_at timestamp with time zone,
    updated_at timestamp with time zone
);

-- Reconcile a local table that Hibernate may have created before the SQL was
-- applied. Production still receives the same canonical constraints below.
ALTER TABLE tbl_overthinking_notification_outbox ADD COLUMN IF NOT EXISTS event_id uuid;
ALTER TABLE tbl_overthinking_notification_outbox ADD COLUMN IF NOT EXISTS recipient_id uuid;
ALTER TABLE tbl_overthinking_notification_outbox ADD COLUMN IF NOT EXISTS notification_type varchar(64);
ALTER TABLE tbl_overthinking_notification_outbox ADD COLUMN IF NOT EXISTS title varchar(160);
ALTER TABLE tbl_overthinking_notification_outbox ADD COLUMN IF NOT EXISTS message varchar(1000);
ALTER TABLE tbl_overthinking_notification_outbox ADD COLUMN IF NOT EXISTS payload jsonb;
ALTER TABLE tbl_overthinking_notification_outbox ADD COLUMN IF NOT EXISTS email_force boolean;
ALTER TABLE tbl_overthinking_notification_outbox ADD COLUMN IF NOT EXISTS occurred_at timestamp with time zone;
ALTER TABLE tbl_overthinking_notification_outbox ADD COLUMN IF NOT EXISTS status varchar(24);
ALTER TABLE tbl_overthinking_notification_outbox ADD COLUMN IF NOT EXISTS attempt_count integer;
ALTER TABLE tbl_overthinking_notification_outbox ADD COLUMN IF NOT EXISTS next_attempt_at timestamp with time zone;
ALTER TABLE tbl_overthinking_notification_outbox ADD COLUMN IF NOT EXISTS lease_owner varchar(100);
ALTER TABLE tbl_overthinking_notification_outbox ADD COLUMN IF NOT EXISTS lease_until timestamp with time zone;
ALTER TABLE tbl_overthinking_notification_outbox ADD COLUMN IF NOT EXISTS last_error_type varchar(200);
ALTER TABLE tbl_overthinking_notification_outbox ADD COLUMN IF NOT EXISTS published_at timestamp with time zone;
ALTER TABLE tbl_overthinking_notification_outbox ADD COLUMN IF NOT EXISTS created_at timestamp with time zone;
ALTER TABLE tbl_overthinking_notification_outbox ADD COLUMN IF NOT EXISTS updated_at timestamp with time zone;

UPDATE tbl_overthinking_notification_outbox SET email_force = false WHERE email_force IS NULL;
UPDATE tbl_overthinking_notification_outbox SET attempt_count = 0 WHERE attempt_count IS NULL;

DO $migration$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM tbl_overthinking_notification_outbox outbox
        WHERE outbox.event_id IS NULL
           OR outbox.recipient_id IS NULL
           OR outbox.notification_type IS NULL
           OR btrim(outbox.notification_type) = ''
           OR outbox.title IS NULL
           OR char_length(btrim(outbox.title)) NOT BETWEEN 1 AND 160
           OR outbox.message IS NULL
           OR char_length(btrim(outbox.message)) NOT BETWEEN 1 AND 1000
           OR outbox.payload IS NULL
           OR jsonb_typeof(outbox.payload) <> 'object'
           OR outbox.occurred_at IS NULL
           OR outbox.status NOT IN ('PENDING', 'IN_FLIGHT', 'PUBLISHED', 'DEAD_LETTER')
           OR outbox.attempt_count < 0
           OR outbox.next_attempt_at IS NULL
           OR outbox.created_at IS NULL
           OR outbox.updated_at IS NULL
           OR ((outbox.status = 'IN_FLIGHT') <> (outbox.lease_owner IS NOT NULL AND outbox.lease_until IS NOT NULL))
           OR ((outbox.status = 'PUBLISHED') <> (outbox.published_at IS NOT NULL))
           OR (outbox.last_error_type IS NOT NULL
               AND char_length(btrim(outbox.last_error_type)) NOT BETWEEN 1 AND 200)
    ) THEN
        RAISE EXCEPTION 'Existing overthinking notification outbox data violates the canonical contract';
    END IF;
END
$migration$;

ALTER TABLE tbl_overthinking_notification_outbox ALTER COLUMN event_id SET NOT NULL;
ALTER TABLE tbl_overthinking_notification_outbox ALTER COLUMN recipient_id SET NOT NULL;
ALTER TABLE tbl_overthinking_notification_outbox ALTER COLUMN notification_type SET NOT NULL;
ALTER TABLE tbl_overthinking_notification_outbox ALTER COLUMN title SET NOT NULL;
ALTER TABLE tbl_overthinking_notification_outbox ALTER COLUMN message SET NOT NULL;
ALTER TABLE tbl_overthinking_notification_outbox ALTER COLUMN payload SET NOT NULL;
ALTER TABLE tbl_overthinking_notification_outbox ALTER COLUMN email_force SET DEFAULT false;
ALTER TABLE tbl_overthinking_notification_outbox ALTER COLUMN email_force SET NOT NULL;
ALTER TABLE tbl_overthinking_notification_outbox ALTER COLUMN occurred_at SET NOT NULL;
ALTER TABLE tbl_overthinking_notification_outbox ALTER COLUMN status SET NOT NULL;
ALTER TABLE tbl_overthinking_notification_outbox ALTER COLUMN attempt_count SET DEFAULT 0;
ALTER TABLE tbl_overthinking_notification_outbox ALTER COLUMN attempt_count SET NOT NULL;
ALTER TABLE tbl_overthinking_notification_outbox ALTER COLUMN next_attempt_at SET NOT NULL;
ALTER TABLE tbl_overthinking_notification_outbox ALTER COLUMN created_at SET NOT NULL;
ALTER TABLE tbl_overthinking_notification_outbox ALTER COLUMN updated_at SET NOT NULL;

DO $migration$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'tbl_overthinking_notification_outbox'::regclass AND contype = 'p'
    ) THEN
        ALTER TABLE tbl_overthinking_notification_outbox
            ADD CONSTRAINT pk_overthinking_notification_outbox PRIMARY KEY (event_id);
    END IF;
END
$migration$;

ALTER TABLE tbl_overthinking_notification_outbox DROP CONSTRAINT IF EXISTS ck_overthinking_notification_outbox_status;
ALTER TABLE tbl_overthinking_notification_outbox
    ADD CONSTRAINT ck_overthinking_notification_outbox_status
    CHECK (status IN ('PENDING', 'IN_FLIGHT', 'PUBLISHED', 'DEAD_LETTER')) NOT VALID;
ALTER TABLE tbl_overthinking_notification_outbox VALIDATE CONSTRAINT ck_overthinking_notification_outbox_status;

ALTER TABLE tbl_overthinking_notification_outbox DROP CONSTRAINT IF EXISTS ck_overthinking_notification_outbox_attempt_count;
ALTER TABLE tbl_overthinking_notification_outbox
    ADD CONSTRAINT ck_overthinking_notification_outbox_attempt_count CHECK (attempt_count >= 0) NOT VALID;
ALTER TABLE tbl_overthinking_notification_outbox VALIDATE CONSTRAINT ck_overthinking_notification_outbox_attempt_count;

ALTER TABLE tbl_overthinking_notification_outbox DROP CONSTRAINT IF EXISTS ck_overthinking_notification_outbox_payload;
ALTER TABLE tbl_overthinking_notification_outbox
    ADD CONSTRAINT ck_overthinking_notification_outbox_payload CHECK (jsonb_typeof(payload) = 'object') NOT VALID;
ALTER TABLE tbl_overthinking_notification_outbox VALIDATE CONSTRAINT ck_overthinking_notification_outbox_payload;

ALTER TABLE tbl_overthinking_notification_outbox DROP CONSTRAINT IF EXISTS ck_overthinking_notification_outbox_title;
ALTER TABLE tbl_overthinking_notification_outbox
    ADD CONSTRAINT ck_overthinking_notification_outbox_title
    CHECK (char_length(btrim(title)) BETWEEN 1 AND 160) NOT VALID;
ALTER TABLE tbl_overthinking_notification_outbox VALIDATE CONSTRAINT ck_overthinking_notification_outbox_title;

ALTER TABLE tbl_overthinking_notification_outbox DROP CONSTRAINT IF EXISTS ck_overthinking_notification_outbox_message;
ALTER TABLE tbl_overthinking_notification_outbox
    ADD CONSTRAINT ck_overthinking_notification_outbox_message
    CHECK (char_length(btrim(message)) BETWEEN 1 AND 1000) NOT VALID;
ALTER TABLE tbl_overthinking_notification_outbox VALIDATE CONSTRAINT ck_overthinking_notification_outbox_message;

ALTER TABLE tbl_overthinking_notification_outbox DROP CONSTRAINT IF EXISTS ck_overthinking_notification_outbox_lease;
ALTER TABLE tbl_overthinking_notification_outbox
    ADD CONSTRAINT ck_overthinking_notification_outbox_lease CHECK (
        (status = 'IN_FLIGHT' AND lease_owner IS NOT NULL AND lease_until IS NOT NULL)
        OR (status <> 'IN_FLIGHT' AND lease_owner IS NULL AND lease_until IS NULL)
    ) NOT VALID;
ALTER TABLE tbl_overthinking_notification_outbox VALIDATE CONSTRAINT ck_overthinking_notification_outbox_lease;

ALTER TABLE tbl_overthinking_notification_outbox DROP CONSTRAINT IF EXISTS ck_overthinking_notification_outbox_published;
ALTER TABLE tbl_overthinking_notification_outbox
    ADD CONSTRAINT ck_overthinking_notification_outbox_published CHECK (
        (status = 'PUBLISHED' AND published_at IS NOT NULL)
        OR (status <> 'PUBLISHED' AND published_at IS NULL)
    ) NOT VALID;
ALTER TABLE tbl_overthinking_notification_outbox VALIDATE CONSTRAINT ck_overthinking_notification_outbox_published;

ALTER TABLE tbl_overthinking_notification_outbox DROP CONSTRAINT IF EXISTS ck_overthinking_notification_outbox_error_type;
ALTER TABLE tbl_overthinking_notification_outbox
    ADD CONSTRAINT ck_overthinking_notification_outbox_error_type CHECK (
        last_error_type IS NULL OR char_length(btrim(last_error_type)) BETWEEN 1 AND 200
    ) NOT VALID;
ALTER TABLE tbl_overthinking_notification_outbox VALIDATE CONSTRAINT ck_overthinking_notification_outbox_error_type;

CREATE INDEX IF NOT EXISTS idx_overthinking_notification_outbox_due
    ON tbl_overthinking_notification_outbox (status, next_attempt_at, created_at);
CREATE INDEX IF NOT EXISTS idx_overthinking_notification_outbox_lease
    ON tbl_overthinking_notification_outbox (status, lease_until);
CREATE INDEX IF NOT EXISTS idx_overthinking_notification_outbox_created
    ON tbl_overthinking_notification_outbox (created_at);
CREATE INDEX IF NOT EXISTS idx_overthinking_notification_outbox_published
    ON tbl_overthinking_notification_outbox (status, published_at);

ALTER TABLE tbl_overthinking_notification_outbox DROP CONSTRAINT IF EXISTS ck_overthinking_notification_outbox_type;
ALTER TABLE tbl_overthinking_notification_outbox ADD CONSTRAINT ck_overthinking_notification_outbox_type
    CHECK (notification_type IN (
        'OVERTHINKING_REVEAL_REQUEST_RECEIVED',
        'OVERTHINKING_REVEAL_REQUEST_APPROVED',
        'OVERTHINKING_REVEAL_REQUEST_REJECTED'
    )) NOT VALID;
ALTER TABLE tbl_overthinking_notification_outbox VALIDATE CONSTRAINT ck_overthinking_notification_outbox_type;

-- Author/actor snapshots are forbidden for pending and rejected requests.
-- Only an approved recipient receives the authorId explicitly granted to them.
ALTER TABLE tbl_overthinking_notification_outbox DROP CONSTRAINT IF EXISTS ck_overthinking_notification_outbox_privacy;
ALTER TABLE tbl_overthinking_notification_outbox ADD CONSTRAINT ck_overthinking_notification_outbox_privacy CHECK (
    payload ?& ARRAY['module','action','postId','postTitle','revealRequestId']
    AND jsonb_typeof(payload -> 'module') = 'string'
    AND jsonb_typeof(payload -> 'action') = 'string'
    AND payload ->> 'module' = 'OVERTHINKING'
    AND jsonb_typeof(payload -> 'postTitle') = 'string'
    AND char_length(btrim(payload ->> 'postTitle')) BETWEEN 1 AND 64
    AND jsonb_typeof(payload -> 'postId') = 'string'
    AND jsonb_typeof(payload -> 'revealRequestId') = 'string'
    AND payload ->> 'postId' ~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
    AND payload ->> 'revealRequestId' ~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
    AND CASE notification_type
        WHEN 'OVERTHINKING_REVEAL_REQUEST_RECEIVED' THEN
            payload ->> 'action' = 'REVEAL_REQUEST_RECEIVED'
            AND payload ? 'requesterId'
            AND jsonb_typeof(payload -> 'requesterId') = 'string'
            AND payload ->> 'requesterId' ~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
            AND payload - ARRAY['module','action','postId','postTitle','revealRequestId','requesterId'] = '{}'::jsonb
        WHEN 'OVERTHINKING_REVEAL_REQUEST_APPROVED' THEN
            payload ->> 'action' = 'REVEAL_REQUEST_APPROVED'
            AND payload ? 'authorId'
            AND jsonb_typeof(payload -> 'authorId') = 'string'
            AND payload ->> 'authorId' ~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
            AND payload - ARRAY['module','action','postId','postTitle','revealRequestId','authorId'] = '{}'::jsonb
        WHEN 'OVERTHINKING_REVEAL_REQUEST_REJECTED' THEN
            payload ->> 'action' = 'REVEAL_REQUEST_REJECTED'
            AND payload - ARRAY['module','action','postId','postTitle','revealRequestId'] = '{}'::jsonb
        ELSE false
    END
) NOT VALID;
ALTER TABLE tbl_overthinking_notification_outbox VALIDATE CONSTRAINT ck_overthinking_notification_outbox_privacy;

COMMENT ON TABLE tbl_overthinking_notification_outbox IS
    'Transactional Overthinking notification snapshots. Keep event_id unchanged on replay; anonymous author identity is absent except on approval.';
INSERT INTO soundconnect_schema_migrations(migration_id)
VALUES ('2026-09-09-overthinking-notification-outbox') ON CONFLICT DO NOTHING;
COMMIT;
