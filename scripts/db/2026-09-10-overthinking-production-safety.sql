-- Additive production safeguards. Apply after overthinking lifecycle/inbox/profile-share migrations.
BEGIN;
SET LOCAL lock_timeout='10s';
SET LOCAL statement_timeout='60s';
CREATE TABLE IF NOT EXISTS soundconnect_schema_migrations (
    migration_id varchar(160) PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS tbl_overthinking_create_receipt (
    owner_user_id uuid NOT NULL REFERENCES tbl_user(id) ON DELETE CASCADE,
    client_request_id uuid NOT NULL,
    request_hash varchar(64) NOT NULL,
    -- Deliberately retained after source deletion: retrying an old operation must never resurrect content.
    post_id uuid NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY(owner_user_id,client_request_id),
    CONSTRAINT ck_overthinking_create_hash CHECK(request_hash ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_overthinking_create_key CHECK(client_request_id <> '00000000-0000-0000-0000-000000000000'::uuid)
);
CREATE TABLE IF NOT EXISTS tbl_overthinking_reveal_attempt (
    id uuid PRIMARY KEY,
    requester_id uuid NOT NULL REFERENCES tbl_user(id) ON DELETE CASCADE,
    author_id uuid NOT NULL REFERENCES tbl_user(id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_overthinking_reveal_attempt_requester_time
    ON tbl_overthinking_reveal_attempt(requester_id,created_at DESC);
CREATE INDEX IF NOT EXISTS ix_overthinking_reveal_attempt_author ON tbl_overthinking_reveal_attempt(author_id);
CREATE INDEX IF NOT EXISTS ix_overthinking_reveal_attempt_cleanup ON tbl_overthinking_reveal_attempt(created_at);
CREATE INDEX IF NOT EXISTS idx_overthinking_post_page ON tbl_overthinking_post(created_at DESC,id DESC);
CREATE INDEX IF NOT EXISTS idx_overthinking_post_artist_page ON tbl_overthinking_post(artist_id,created_at DESC,id DESC);
CREATE INDEX IF NOT EXISTS idx_overthinking_post_author_page ON tbl_overthinking_post(author_id,created_at DESC,id DESC);
CREATE INDEX IF NOT EXISTS idx_overthinking_outbox_source_post
    ON tbl_overthinking_notification_outbox((payload->>'postId'));
CREATE INDEX IF NOT EXISTS idx_overthinking_notification_source_post
    ON tbl_notification((payload->>'postId'))
    WHERE type IN ('OVERTHINKING_REVEAL_REQUEST_RECEIVED','OVERTHINKING_REVEAL_REQUEST_APPROVED','OVERTHINKING_REVEAL_REQUEST_REJECTED');
-- Defence in depth for retained rows. Java also sanitizes every response, including during rolling deployment.
UPDATE tbl_overthinking_post SET spotify_album_image_url=NULL
WHERE spotify_album_image_url IS NOT NULL
  AND spotify_album_image_url !~* '^https://i[.]scdn[.]co(:443)?/image/[A-Za-z0-9]+$';
INSERT INTO soundconnect_schema_migrations(migration_id) VALUES('2026-09-10-overthinking-production-safety') ON CONFLICT DO NOTHING;
COMMIT;
