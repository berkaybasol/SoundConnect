-- SoundConnect media lifecycle status rollout.
--
-- This script is deliberately one PostgreSQL transaction. PostgreSQL DDL is
-- transactional, so a failed statement leaves neither a partially widened
-- status model nor a partially backfilled producer fence behind. Execute it
-- with a client configured to stop on the first error (psql: ON_ERROR_STOP=1).

BEGIN;

-- Migrations run before application traffic is admitted. Taking the lock up
-- front also prevents an old node from writing a legacy state halfway through
-- the constraint replacement/backfill sequence.
LOCK TABLE tbl_media_asset IN ACCESS EXCLUSIVE MODE;

ALTER TABLE tbl_media_asset
    ADD COLUMN IF NOT EXISTS deletion_requested_at timestamp without time zone;

ALTER TABLE tbl_media_asset
    ADD COLUMN IF NOT EXISTS upload_write_authority_expires_at timestamp without time zone;

ALTER TABLE tbl_media_asset
    ADD COLUMN IF NOT EXISTS physical_deletion_not_before timestamp without time zone;

ALTER TABLE tbl_media_asset
    ADD COLUMN IF NOT EXISTS upload_verification_attempt_token uuid;

ALTER TABLE tbl_media_asset
    ADD COLUMN IF NOT EXISTS upload_verification_lease_expires_at timestamp without time zone;

ALTER TABLE tbl_media_asset
    ADD COLUMN IF NOT EXISTS upload_verification_attempt_deadline timestamp without time zone;

ALTER TABLE tbl_media_asset
    ADD COLUMN IF NOT EXISTS upload_verification_cleanup_not_before timestamp without time zone;

ALTER TABLE tbl_media_asset
    ADD COLUMN IF NOT EXISTS transcode_attempt_token uuid;

ALTER TABLE tbl_media_asset
    ADD COLUMN IF NOT EXISTS transcode_lease_until timestamp without time zone;

ALTER TABLE tbl_media_asset
    ADD COLUMN IF NOT EXISTS transcode_attempt_deadline timestamp without time zone;

ALTER TABLE tbl_media_asset
    ADD COLUMN IF NOT EXISTS transcode_cleanup_not_before timestamp without time zone;

ALTER TABLE tbl_media_asset
    ADD COLUMN IF NOT EXISTS transcode_attempt_count integer DEFAULT 0;

ALTER TABLE tbl_media_asset
    ADD COLUMN IF NOT EXISTS transcode_retry_pending boolean DEFAULT false;

ALTER TABLE tbl_media_asset
    ADD COLUMN IF NOT EXISTS transcode_retain_source_after_cleanup boolean DEFAULT false;

-- Replace every single-column CHECK on status, including a Hibernate-generated
-- constraint whose name may differ between environments. Multi-column
-- lifecycle invariants are intentionally left alone. This must happen before
-- PROCESSING is rewritten to the new HLS_CLEANUP state.
ALTER TABLE tbl_media_asset
    DROP CONSTRAINT IF EXISTS tbl_media_asset_status_check;

DO $migration$
DECLARE
    legacy_status_constraint text;
BEGIN
    FOR legacy_status_constraint IN
        SELECT constraint_definition.conname
        FROM pg_constraint constraint_definition
        JOIN pg_attribute status_column
          ON status_column.attrelid = constraint_definition.conrelid
         AND status_column.attname = 'status'
        WHERE constraint_definition.conrelid = 'tbl_media_asset'::regclass
          AND constraint_definition.contype = 'c'
          AND cardinality(constraint_definition.conkey) = 1
          AND status_column.attnum = ANY (constraint_definition.conkey)
    LOOP
        EXECUTE format(
            'ALTER TABLE tbl_media_asset DROP CONSTRAINT %I',
            legacy_status_constraint
        );
    END LOOP;
END
$migration$;

ALTER TABLE tbl_media_asset
    ADD CONSTRAINT tbl_media_asset_status_check
    CHECK (status IN (
        'UPLOADING',
        'VERIFYING',
        'CLEANUP_PENDING',
        'DELETION_PENDING',
        'TRANSCODE_QUEUED',
        'TRANSCODE_SENT',
        'PROCESSING',
        'HLS_CLEANUP',
        'READY',
        'FAILED'
    ));

UPDATE tbl_media_asset
SET transcode_attempt_count = 0
WHERE transcode_attempt_count IS NULL;

UPDATE tbl_media_asset
SET transcode_retry_pending = false
WHERE transcode_retry_pending IS NULL;

UPDATE tbl_media_asset
SET transcode_retain_source_after_cleanup = false
WHERE transcode_retain_source_after_cleanup IS NULL;

ALTER TABLE tbl_media_asset
    ALTER COLUMN transcode_attempt_count SET DEFAULT 0,
    ALTER COLUMN transcode_attempt_count SET NOT NULL,
    ALTER COLUMN transcode_retry_pending SET DEFAULT false,
    ALTER COLUMN transcode_retry_pending SET NOT NULL,
    ALTER COLUMN transcode_retain_source_after_cleanup SET DEFAULT false,
    ALTER COLUMN transcode_retain_source_after_cleanup SET NOT NULL;

-- A rolling deployment can leave an old, tokenless PROCESSING worker alive.
-- Recover it through the normal expired-lease path, but never reuse/delete its
-- deterministic HLS prefix before the legacy 12-hour producer bound ends.
UPDATE tbl_media_asset
SET status = 'HLS_CLEANUP',
    transcode_attempt_count = GREATEST(transcode_attempt_count, 1),
    transcode_attempt_deadline = COALESCE(
        transcode_attempt_deadline,
        (CURRENT_TIMESTAMP AT TIME ZONE 'UTC') + INTERVAL '12 hours'
    ),
    transcode_cleanup_not_before = COALESCE(
        transcode_cleanup_not_before,
        transcode_attempt_deadline,
        (CURRENT_TIMESTAMP AT TIME ZONE 'UTC') + INTERVAL '12 hours'
    ),
    transcode_retry_pending = true,
    transcode_retain_source_after_cleanup = false,
    updated_at = CURRENT_TIMESTAMP AT TIME ZONE 'UTC'
WHERE status = 'PROCESSING'
  AND transcode_attempt_token IS NULL;

-- Heal a database on which an older, non-transactional revision of this
-- migration stopped after introducing HLS_CLEANUP but before its safety fence.
UPDATE tbl_media_asset
SET transcode_cleanup_not_before = COALESCE(
        transcode_attempt_deadline,
        (CURRENT_TIMESTAMP AT TIME ZONE 'UTC') + INTERVAL '12 hours'
    )
WHERE status = 'HLS_CLEANUP'
  AND transcode_retry_pending
  AND transcode_cleanup_not_before IS NULL;

-- Rolling-upgrade safety: old rows did not persist the actual signing instant
-- or TTL. One maximum supported TTL plus maximum skew from migration time is
-- deliberately conservative and cannot be shortened by later configuration.
UPDATE tbl_media_asset
SET upload_write_authority_expires_at =
        (CURRENT_TIMESTAMP AT TIME ZONE 'UTC') + INTERVAL '1 hour 15 minutes'
WHERE upload_write_authority_expires_at IS NULL;

-- Existing local/staging deletion intents predate the dedicated marker. Their
-- last update is the safest available lower bound for the producer grace window.
UPDATE tbl_media_asset
SET deletion_requested_at = updated_at
WHERE status = 'DELETION_PENDING'
  AND deletion_requested_at IS NULL;

UPDATE tbl_media_asset
SET physical_deletion_not_before = CASE
        WHEN kind = 'VIDEO' THEN deletion_requested_at + INTERVAL '14 hours'
        WHEN kind = 'IMAGE' AND visibility = 'PUBLIC'
            THEN deletion_requested_at + INTERVAL '5 minutes'
        ELSE deletion_requested_at
    END
WHERE status = 'DELETION_PENDING'
  AND physical_deletion_not_before IS NULL;

-- A rolling node may already have committed VERIFYING. The deterministic UUID
-- is only an internal ownership fence (not a secret), is unique per asset, and
-- avoids requiring pgcrypto/gen_random_uuid on older PostgreSQL installations.
UPDATE tbl_media_asset
SET upload_verification_attempt_token = COALESCE(
            upload_verification_attempt_token,
            md5('soundconnect:legacy-upload-verification:' || id::text)::uuid
        ),
    upload_verification_lease_expires_at = COALESCE(
            upload_verification_lease_expires_at,
            (CURRENT_TIMESTAMP AT TIME ZONE 'UTC') - INTERVAL '1 second'
        ),
    upload_verification_attempt_deadline = COALESCE(
            upload_verification_attempt_deadline,
            (CURRENT_TIMESTAMP AT TIME ZONE 'UTC') + INTERVAL '5 minutes'
        ),
    upload_verification_cleanup_not_before = COALESCE(
            upload_verification_cleanup_not_before,
            (CURRENT_TIMESTAMP AT TIME ZONE 'UTC') + INTERVAL '6 minutes 30 seconds'
        )
WHERE status = 'VERIFYING';

ALTER TABLE tbl_media_asset
    DROP CONSTRAINT IF EXISTS tbl_media_asset_transcode_attempt_count_check;

ALTER TABLE tbl_media_asset
    ADD CONSTRAINT tbl_media_asset_transcode_attempt_count_check
    CHECK (transcode_attempt_count >= 0);

ALTER TABLE tbl_media_asset
    DROP CONSTRAINT IF EXISTS tbl_media_asset_processing_lease_check;

ALTER TABLE tbl_media_asset
    ADD CONSTRAINT tbl_media_asset_processing_lease_check
    CHECK (
        status <> 'PROCESSING'
        OR (
            transcode_attempt_token IS NOT NULL
            AND transcode_lease_until IS NOT NULL
            AND transcode_attempt_deadline IS NOT NULL
        )
    );

ALTER TABLE tbl_media_asset
    DROP CONSTRAINT IF EXISTS tbl_media_asset_retry_cleanup_deadline_check;

ALTER TABLE tbl_media_asset
    ADD CONSTRAINT tbl_media_asset_retry_cleanup_deadline_check
    CHECK (
        NOT (status = 'HLS_CLEANUP' AND transcode_retry_pending)
        OR transcode_cleanup_not_before IS NOT NULL
    );

ALTER TABLE tbl_media_asset
    DROP CONSTRAINT IF EXISTS tbl_media_asset_cleanup_disposition_check;

ALTER TABLE tbl_media_asset
    ADD CONSTRAINT tbl_media_asset_cleanup_disposition_check
    CHECK (NOT (transcode_retry_pending AND transcode_retain_source_after_cleanup));

ALTER TABLE tbl_media_asset
    DROP CONSTRAINT IF EXISTS tbl_media_asset_verification_fence_check;

ALTER TABLE tbl_media_asset
    ADD CONSTRAINT tbl_media_asset_verification_fence_check
    CHECK (
        status <> 'VERIFYING'
        OR (
            upload_verification_attempt_token IS NOT NULL
            AND upload_verification_lease_expires_at IS NOT NULL
            AND upload_verification_attempt_deadline IS NOT NULL
            AND upload_verification_cleanup_not_before IS NOT NULL
        )
    );

CREATE INDEX IF NOT EXISTS idx_media_transcode_lease
    ON tbl_media_asset (status, transcode_lease_until);

CREATE INDEX IF NOT EXISTS idx_media_verification_lease
    ON tbl_media_asset (status, upload_verification_lease_expires_at);

CREATE INDEX IF NOT EXISTS idx_media_verification_cleanup
    ON tbl_media_asset (upload_verification_cleanup_not_before)
    WHERE upload_verification_cleanup_not_before IS NOT NULL;

COMMIT;
