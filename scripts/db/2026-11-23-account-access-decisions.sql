-- Apply before serving the new API. Older binaries cannot enforce access_suspended.
-- The zero revision represents a pre-existing account without a moderation decision.
BEGIN;
ALTER TABLE tbl_user
    ADD COLUMN IF NOT EXISTS access_suspended boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS access_revision bigint NOT NULL DEFAULT 0;

-- Account-local decision history. The reason is private to authorized panel readers.
-- Retain the decision-time references without preventing existing account erasure paths.
CREATE TABLE IF NOT EXISTS tbl_account_access_decision (
    target_id uuid NOT NULL,
    revision bigint NOT NULL CHECK (revision > 0),
    actor_id uuid NOT NULL,
    actor_username varchar(255) NOT NULL,
    request_id uuid NOT NULL,
    was_suspended boolean NOT NULL,
    suspended boolean NOT NULL,
    reason varchar(500) NOT NULL CHECK (char_length(trim(reason)) BETWEEN 5 AND 500),
    decided_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (target_id, revision),
    UNIQUE (actor_id, request_id),
    CHECK (was_suspended <> suspended)
);
CREATE INDEX IF NOT EXISTS idx_user_access_suspended
    ON tbl_user (user_name, id) WHERE access_suspended AND erased_at IS NULL;
COMMIT;
