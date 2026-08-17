-- Durable Collab notification delivery plus shared notification-consumer
-- deduplication and event-time persistence.
-- Run with psql ON_ERROR_STOP=1 after 2026-08-11-collab-domain.sql.
-- The migration is transactional and intentionally rerunnable.

BEGIN;

CREATE TABLE IF NOT EXISTS tbl_collab_notification_outbox (
    event_id uuid NOT NULL,
    recipient_id uuid NOT NULL,
    notification_type varchar(64) NOT NULL,
    title varchar(160) NOT NULL,
    message varchar(1000) NOT NULL,
    payload jsonb NOT NULL,
    email_force boolean NOT NULL DEFAULT false,
    occurred_at timestamp with time zone NOT NULL,
    status varchar(24) NOT NULL,
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamp with time zone NOT NULL,
    lease_owner varchar(100),
    lease_until timestamp with time zone,
    last_error_type varchar(200),
    published_at timestamp with time zone,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT pk_collab_notification_outbox PRIMARY KEY (event_id),
    CONSTRAINT ck_collab_notification_outbox_status CHECK (
        status IN ('PENDING', 'IN_FLIGHT', 'PUBLISHED', 'DEAD_LETTER')
    ),
    CONSTRAINT ck_collab_notification_outbox_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT ck_collab_notification_outbox_payload CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT ck_collab_notification_outbox_title CHECK (
        char_length(btrim(title)) BETWEEN 1 AND 160
    ),
    CONSTRAINT ck_collab_notification_outbox_message CHECK (
        char_length(btrim(message)) BETWEEN 1 AND 1000
    ),
    CONSTRAINT ck_collab_notification_outbox_lease CHECK (
        (status = 'IN_FLIGHT' AND lease_owner IS NOT NULL AND lease_until IS NOT NULL)
        OR
        (status <> 'IN_FLIGHT' AND lease_owner IS NULL AND lease_until IS NULL)
    ),
    CONSTRAINT ck_collab_notification_outbox_published CHECK (
        (status = 'PUBLISHED' AND published_at IS NOT NULL)
        OR
        (status <> 'PUBLISHED' AND published_at IS NULL)
    ),
    CONSTRAINT ck_collab_notification_outbox_error_type CHECK (
        last_error_type IS NULL OR char_length(btrim(last_error_type)) BETWEEN 1 AND 200
    )
);

-- `CREATE TABLE IF NOT EXISTS` deliberately does not reconcile a table that a
-- one-time local Hibernate bootstrap created first. Add every safety constraint
-- independently so that both fresh SQL-owned installs and upgraded local
-- schemas end in the same state.
DO $collab_notification_outbox_constraints$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'tbl_collab_notification_outbox'::regclass
           AND conname = 'ck_collab_notification_outbox_status'
    ) THEN
        ALTER TABLE tbl_collab_notification_outbox
            ADD CONSTRAINT ck_collab_notification_outbox_status
            CHECK (status IN ('PENDING', 'IN_FLIGHT', 'PUBLISHED', 'DEAD_LETTER'));
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'tbl_collab_notification_outbox'::regclass
           AND conname = 'ck_collab_notification_outbox_attempt_count'
    ) THEN
        ALTER TABLE tbl_collab_notification_outbox
            ADD CONSTRAINT ck_collab_notification_outbox_attempt_count
            CHECK (attempt_count >= 0);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'tbl_collab_notification_outbox'::regclass
           AND conname = 'ck_collab_notification_outbox_payload'
    ) THEN
        ALTER TABLE tbl_collab_notification_outbox
            ADD CONSTRAINT ck_collab_notification_outbox_payload
            CHECK (jsonb_typeof(payload) = 'object');
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'tbl_collab_notification_outbox'::regclass
           AND conname = 'ck_collab_notification_outbox_title'
    ) THEN
        ALTER TABLE tbl_collab_notification_outbox
            ADD CONSTRAINT ck_collab_notification_outbox_title
            CHECK (char_length(btrim(title)) BETWEEN 1 AND 160);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'tbl_collab_notification_outbox'::regclass
           AND conname = 'ck_collab_notification_outbox_message'
    ) THEN
        ALTER TABLE tbl_collab_notification_outbox
            ADD CONSTRAINT ck_collab_notification_outbox_message
            CHECK (char_length(btrim(message)) BETWEEN 1 AND 1000);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'tbl_collab_notification_outbox'::regclass
           AND conname = 'ck_collab_notification_outbox_lease'
    ) THEN
        ALTER TABLE tbl_collab_notification_outbox
            ADD CONSTRAINT ck_collab_notification_outbox_lease
            CHECK (
                (status = 'IN_FLIGHT' AND lease_owner IS NOT NULL AND lease_until IS NOT NULL)
                OR
                (status <> 'IN_FLIGHT' AND lease_owner IS NULL AND lease_until IS NULL)
            );
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'tbl_collab_notification_outbox'::regclass
           AND conname = 'ck_collab_notification_outbox_published'
    ) THEN
        ALTER TABLE tbl_collab_notification_outbox
            ADD CONSTRAINT ck_collab_notification_outbox_published
            CHECK (
                (status = 'PUBLISHED' AND published_at IS NOT NULL)
                OR
                (status <> 'PUBLISHED' AND published_at IS NULL)
            );
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'tbl_collab_notification_outbox'::regclass
           AND conname = 'ck_collab_notification_outbox_error_type'
    ) THEN
        ALTER TABLE tbl_collab_notification_outbox
            ADD CONSTRAINT ck_collab_notification_outbox_error_type
            CHECK (
                last_error_type IS NULL
                OR char_length(btrim(last_error_type)) BETWEEN 1 AND 200
            );
    END IF;
END
$collab_notification_outbox_constraints$;

CREATE INDEX IF NOT EXISTS idx_collab_notification_outbox_due
    ON tbl_collab_notification_outbox (status, next_attempt_at, created_at);

CREATE INDEX IF NOT EXISTS idx_collab_notification_outbox_lease
    ON tbl_collab_notification_outbox (status, lease_until);

CREATE INDEX IF NOT EXISTS idx_collab_notification_outbox_created
    ON tbl_collab_notification_outbox (created_at);

CREATE INDEX IF NOT EXISTS idx_collab_notification_outbox_published
    ON tbl_collab_notification_outbox (status, published_at);

ALTER TABLE tbl_notification
    ADD COLUMN IF NOT EXISTS source_event_id uuid,
    ADD COLUMN IF NOT EXISTS occurred_at timestamp with time zone;

-- Existing rows predate producer event timestamps. BaseEntity.created_at is
-- stored as a UTC timestamp without time zone, so preserve that instant during
-- the one-time backfill. New/legacy consumer writes always populate occurred_at.
UPDATE tbl_notification
   SET occurred_at = COALESCE(
           occurred_at,
           created_at AT TIME ZONE 'UTC',
           CURRENT_TIMESTAMP
       )
 WHERE occurred_at IS NULL;

ALTER TABLE tbl_notification
    ALTER COLUMN occurred_at SET NOT NULL;

DO $notification_dedupe_constraint$
BEGIN
    IF NOT EXISTS (
            SELECT 1
              FROM pg_constraint
             WHERE conrelid = to_regclass(format('%I.%I', current_schema(), 'tbl_notification'))
               AND conname = 'uk_notification_source_event_id'
       ) THEN
        EXECUTE format(
            'ALTER TABLE %I.%I ADD CONSTRAINT %I UNIQUE (source_event_id)',
            current_schema(),
            'tbl_notification',
            'uk_notification_source_event_id'
        );
    END IF;
END
$notification_dedupe_constraint$;

CREATE INDEX IF NOT EXISTS idx_notification_recipient_occurred
    ON tbl_notification (recipient_id, occurred_at DESC, id DESC);

COMMIT;
