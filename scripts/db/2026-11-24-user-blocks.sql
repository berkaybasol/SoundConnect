CREATE TABLE IF NOT EXISTS tbl_user_block (
    blocker_id uuid NOT NULL REFERENCES tbl_user(id) ON DELETE CASCADE,
    blocked_id uuid NOT NULL REFERENCES tbl_user(id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    unblocked_at timestamptz,
    PRIMARY KEY (blocker_id, blocked_id),
    CHECK (blocker_id <> blocked_id)
);

CREATE INDEX IF NOT EXISTS idx_user_block_incoming ON tbl_user_block (blocked_id, blocker_id);
CREATE INDEX IF NOT EXISTS idx_user_block_page ON tbl_user_block (blocker_id, created_at DESC, blocked_id);
