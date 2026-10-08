-- Durable studio reservation notifications. Explicit forward migration; rerunnable.
-- Apply with psql ON_ERROR_STOP=1 before starting the new backend.
-- The existing notification receipt/deduplication migrations remain prerequisites.
-- No native push allowlist/capability or application decision behavior changes here.

BEGIN;

CREATE TABLE IF NOT EXISTS tbl_studio_reservation_notification_outbox (
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
    CONSTRAINT pk_studio_reservation_notification_outbox PRIMARY KEY (event_id)
);

-- Reconcile checks on both fresh SQL schemas and an existing Hibernate-created
-- table. Invalid legacy rows abort the migration; do not silently repair/drop data.
DO $studio_reservation_notification_outbox_constraints$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'tbl_studio_reservation_notification_outbox'::regclass
           AND conname = 'ck_studio_reservation_notification_outbox_status'
    ) THEN
        ALTER TABLE tbl_studio_reservation_notification_outbox
            ADD CONSTRAINT ck_studio_reservation_notification_outbox_status
            CHECK (status IN ('PENDING', 'IN_FLIGHT', 'PUBLISHED', 'DEAD_LETTER'));
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'tbl_studio_reservation_notification_outbox'::regclass
           AND conname = 'ck_studio_reservation_notification_outbox_attempt_count'
    ) THEN
        ALTER TABLE tbl_studio_reservation_notification_outbox
            ADD CONSTRAINT ck_studio_reservation_notification_outbox_attempt_count
            CHECK (attempt_count >= 0);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'tbl_studio_reservation_notification_outbox'::regclass
           AND conname = 'ck_studio_reservation_notification_outbox_payload'
    ) THEN
        ALTER TABLE tbl_studio_reservation_notification_outbox
            ADD CONSTRAINT ck_studio_reservation_notification_outbox_payload
            CHECK (jsonb_typeof(payload) = 'object');
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'tbl_studio_reservation_notification_outbox'::regclass
           AND conname = 'ck_studio_reservation_notification_outbox_title'
    ) THEN
        ALTER TABLE tbl_studio_reservation_notification_outbox
            ADD CONSTRAINT ck_studio_reservation_notification_outbox_title
            CHECK (char_length(btrim(title)) BETWEEN 1 AND 160);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'tbl_studio_reservation_notification_outbox'::regclass
           AND conname = 'ck_studio_reservation_notification_outbox_message'
    ) THEN
        ALTER TABLE tbl_studio_reservation_notification_outbox
            ADD CONSTRAINT ck_studio_reservation_notification_outbox_message
            CHECK (char_length(btrim(message)) BETWEEN 1 AND 1000);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'tbl_studio_reservation_notification_outbox'::regclass
           AND conname = 'ck_studio_reservation_notification_outbox_lease'
    ) THEN
        ALTER TABLE tbl_studio_reservation_notification_outbox
            ADD CONSTRAINT ck_studio_reservation_notification_outbox_lease
            CHECK ((status = 'IN_FLIGHT' AND lease_owner IS NOT NULL AND char_length(btrim(lease_owner)) BETWEEN 1 AND 100 AND lease_until IS NOT NULL)
        OR (status <> 'IN_FLIGHT' AND lease_owner IS NULL AND lease_until IS NULL));
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'tbl_studio_reservation_notification_outbox'::regclass
           AND conname = 'ck_studio_reservation_notification_outbox_published'
    ) THEN
        ALTER TABLE tbl_studio_reservation_notification_outbox
            ADD CONSTRAINT ck_studio_reservation_notification_outbox_published
            CHECK ((status = 'PUBLISHED' AND published_at IS NOT NULL)
        OR (status <> 'PUBLISHED' AND published_at IS NULL));
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'tbl_studio_reservation_notification_outbox'::regclass
           AND conname = 'ck_studio_reservation_notification_outbox_error_type'
    ) THEN
        ALTER TABLE tbl_studio_reservation_notification_outbox
            ADD CONSTRAINT ck_studio_reservation_notification_outbox_error_type
            CHECK (last_error_type IS NULL OR char_length(btrim(last_error_type)) BETWEEN 1 AND 200);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'tbl_studio_reservation_notification_outbox'::regclass
           AND conname = 'ck_studio_reservation_notification_outbox_email_force'
    ) THEN
        ALTER TABLE tbl_studio_reservation_notification_outbox
            ADD CONSTRAINT ck_studio_reservation_notification_outbox_email_force
            CHECK (email_force = false);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'tbl_studio_reservation_notification_outbox'::regclass
           AND conname = 'ck_studio_reservation_notification_outbox_type'
    ) THEN
        ALTER TABLE tbl_studio_reservation_notification_outbox
            ADD CONSTRAINT ck_studio_reservation_notification_outbox_type
            CHECK (notification_type IN ('STUDIO_RESERVATION_CREATED', 'STUDIO_RESERVATION_CONFLICTING_REQUESTS', 'STUDIO_RESERVATION_APPROVED', 'STUDIO_RESERVATION_REJECTED', 'STUDIO_RESERVATION_CANCELLED_BY_CUSTOMER', 'STUDIO_RESERVATION_CANCELLED_BY_STUDIO'));
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'tbl_studio_reservation_notification_outbox'::regclass
           AND conname = 'ck_studio_reservation_notification_outbox_action'
    ) THEN
        ALTER TABLE tbl_studio_reservation_notification_outbox
            ADD CONSTRAINT ck_studio_reservation_notification_outbox_action
            CHECK (COALESCE(payload ->> 'module' = 'STUDIO' AND (
            (notification_type = 'STUDIO_RESERVATION_CREATED' AND payload ->> 'action' = 'CREATED')
            OR (notification_type = 'STUDIO_RESERVATION_CONFLICTING_REQUESTS' AND payload ->> 'action' = 'CONFLICTING_REQUESTS')
            OR (notification_type = 'STUDIO_RESERVATION_APPROVED' AND payload ->> 'action' = 'APPROVED')
            OR (notification_type = 'STUDIO_RESERVATION_REJECTED' AND payload ->> 'action' IN ('REJECTED', 'AUTO_REJECTED_CONFLICT'))
            OR (notification_type = 'STUDIO_RESERVATION_CANCELLED_BY_CUSTOMER' AND payload ->> 'action' = 'CANCELLED_BY_CUSTOMER')
            OR (notification_type = 'STUDIO_RESERVATION_CANCELLED_BY_STUDIO' AND payload ->> 'action' IN ('CANCELLED_BY_STUDIO', 'CANCELLED_BY_STUDIO_ROOM_ARCHIVED'))
        ), false));
    END IF;

END
$studio_reservation_notification_outbox_constraints$;

CREATE INDEX IF NOT EXISTS idx_studio_reservation_notification_outbox_due
    ON tbl_studio_reservation_notification_outbox (status, next_attempt_at, created_at);

CREATE INDEX IF NOT EXISTS idx_studio_reservation_notification_outbox_lease
    ON tbl_studio_reservation_notification_outbox (status, lease_until);

CREATE INDEX IF NOT EXISTS idx_studio_reservation_notification_outbox_created
    ON tbl_studio_reservation_notification_outbox (created_at);

CREATE INDEX IF NOT EXISTS idx_studio_reservation_notification_outbox_published
    ON tbl_studio_reservation_notification_outbox (status, published_at);

COMMIT;

