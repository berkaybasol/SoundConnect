-- Apply before enabling the receipt-aware notification writers and deletion API.
-- Minimal technical identities survive deletion/retention; no body, title, or
-- avatar is copied. Existing null-source legacy notifications cannot be linked
-- to a prior queue event and are intentionally not assigned an invented ID.
BEGIN;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '60s';
CREATE TABLE IF NOT EXISTS soundconnect_schema_migrations (
    migration_id varchar(160) PRIMARY KEY,
    applied_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS tbl_notification_receipt (
    source_event_id uuid PRIMARY KEY,
    recipient_id uuid NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO tbl_notification_receipt(source_event_id, recipient_id, recorded_at)
SELECT source_event_id, recipient_id, now()
FROM tbl_notification WHERE source_event_id IS NOT NULL
ON CONFLICT DO NOTHING;
COMMENT ON TABLE tbl_notification_receipt IS
    'Permanent minimal replay receipts. Do not purge when inbox content is deleted; no notification body or identity snapshot is retained.';
INSERT INTO soundconnect_schema_migrations(migration_id)
VALUES ('2026-09-07-notification-replay-receipts') ON CONFLICT DO NOTHING;
COMMIT;
