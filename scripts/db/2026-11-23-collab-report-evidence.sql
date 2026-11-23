-- Older immutable snapshots include both report-time captures and backfills
-- from the moderation rollout. Their capture times cannot be reconstructed.
ALTER TABLE tbl_collab_report
    ADD COLUMN IF NOT EXISTS evidence_captured_at_report boolean NOT NULL DEFAULT false;
