-- Additive/replayable. No provider contact and no application user mutations.
BEGIN;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '60s';

-- Intentionally no content or cascading source FK: moderation must not release
-- a committed client key and allow a delayed retry to recreate removed content.
CREATE TABLE IF NOT EXISTS tbl_dm_send_receipt (
    sender_id uuid NOT NULL,
    client_message_id uuid NOT NULL,
    conversation_id uuid NOT NULL,
    recipient_id uuid NOT NULL,
    message_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (sender_id, client_message_id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_dm_send_receipt_message ON tbl_dm_send_receipt(message_id);

CREATE TABLE IF NOT EXISTS soundconnect_schema_migrations (
    migration_id varchar(160) PRIMARY KEY,
    applied_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO soundconnect_schema_migrations(migration_id)
VALUES ('2026-09-23-dm-send-reliability') ON CONFLICT DO NOTHING;
COMMIT;
