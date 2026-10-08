-- Additive/replayable indexes for bounded DM keyset pages and latest-message projection.
BEGIN;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '60s';
CREATE INDEX IF NOT EXISTS idx_dm_conversation_user_a_page
    ON tbl_dm_conversation(user_a_id, last_message_at DESC NULLS LAST, id DESC);
CREATE INDEX IF NOT EXISTS idx_dm_conversation_user_b_page
    ON tbl_dm_conversation(user_b_id, last_message_at DESC NULLS LAST, id DESC);
CREATE INDEX IF NOT EXISTS idx_dm_message_latest_visible
    ON tbl_dm_message(conversation_id, created_at DESC, id DESC) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_dm_venue_owner_preview
    ON tbl_venues(owner_id, created_at, id);
CREATE TABLE IF NOT EXISTS soundconnect_schema_migrations (
    migration_id varchar(160) PRIMARY KEY,
    applied_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO soundconnect_schema_migrations(migration_id)
VALUES ('2026-09-23-dm-conversation-pagination') ON CONFLICT DO NOTHING;
COMMIT;
