-- Additive migration. Existing application binaries remain compatible: the
-- INSERT trigger assigns revisions even when the application does not know
-- about the new column. Back up first; run with psql -v ON_ERROR_STOP=1.
-- Brief table locks serialize backfill with writers; lock timeout rolls back
-- the entire migration rather than requiring the running API to be stopped.
BEGIN;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '60s';

CREATE TABLE IF NOT EXISTS soundconnect_schema_migrations (
    migration_id varchar(160) PRIMARY KEY,
    applied_at timestamptz NOT NULL DEFAULT now()
);

LOCK TABLE tbl_overthinking_reveal_request IN SHARE ROW EXCLUSIVE MODE;
ALTER TABLE tbl_overthinking_reveal_request ADD COLUMN IF NOT EXISTS inbox_revision bigint;

CREATE TABLE IF NOT EXISTS tbl_overthinking_reveal_inbox (
    author_id uuid PRIMARY KEY REFERENCES tbl_user(id) ON DELETE CASCADE,
    latest_revision bigint NOT NULL DEFAULT 0,
    seen_revision bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_overthinking_inbox_revisions
        CHECK (seen_revision >= 0 AND latest_revision >= seen_revision)
);
LOCK TABLE tbl_overthinking_reveal_inbox IN SHARE ROW EXCLUSIVE MODE;

-- Stable initial order; reruns preserve every already assigned revision and
-- read watermark. Existing requests start unread, regardless of their decision.
WITH known AS (
    SELECT author_id, max(inbox_revision) AS revision
    FROM tbl_overthinking_reveal_request GROUP BY author_id
    UNION ALL
    SELECT author_id, latest_revision FROM tbl_overthinking_reveal_inbox
), maxima AS (
    SELECT author_id, coalesce(max(revision), 0) AS revision FROM known GROUP BY author_id
), missing AS (
    SELECT request.id,
           coalesce(maxima.revision, 0) + row_number() OVER (
               PARTITION BY request.author_id ORDER BY request.created_at, request.id
           ) AS revision
    FROM tbl_overthinking_reveal_request request
    LEFT JOIN maxima ON maxima.author_id = request.author_id
    WHERE request.inbox_revision IS NULL
)
UPDATE tbl_overthinking_reveal_request request SET inbox_revision = missing.revision
FROM missing WHERE request.id = missing.id;

INSERT INTO tbl_overthinking_reveal_inbox(author_id, latest_revision, seen_revision)
SELECT author_id, max(inbox_revision), 0
FROM tbl_overthinking_reveal_request GROUP BY author_id
ON CONFLICT (author_id) DO UPDATE
SET latest_revision = greatest(tbl_overthinking_reveal_inbox.latest_revision, excluded.latest_revision);

ALTER TABLE tbl_overthinking_reveal_request ALTER COLUMN inbox_revision SET NOT NULL;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conrelid = 'tbl_overthinking_reveal_request'::regclass
                     AND conname = 'ck_overthinking_reveal_inbox_revision') THEN
        ALTER TABLE tbl_overthinking_reveal_request
            ADD CONSTRAINT ck_overthinking_reveal_inbox_revision CHECK (inbox_revision > 0);
    END IF;
END $$;
CREATE UNIQUE INDEX IF NOT EXISTS uk_overthinking_reveal_author_revision
    ON tbl_overthinking_reveal_request(author_id, inbox_revision);

CREATE OR REPLACE FUNCTION assign_overthinking_reveal_inbox_revision()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO tbl_overthinking_reveal_inbox(author_id, latest_revision, seen_revision)
    VALUES (NEW.author_id, 1, 0)
    ON CONFLICT (author_id) DO UPDATE
    SET latest_revision = tbl_overthinking_reveal_inbox.latest_revision + 1
    RETURNING latest_revision INTO NEW.inbox_revision;
    RETURN NEW;
END $$;
CREATE OR REPLACE TRIGGER trg_assign_overthinking_reveal_inbox_revision
BEFORE INSERT ON tbl_overthinking_reveal_request
FOR EACH ROW EXECUTE FUNCTION assign_overthinking_reveal_inbox_revision();

INSERT INTO soundconnect_schema_migrations(migration_id)
VALUES ('2026-09-10-overthinking-inbox-seen') ON CONFLICT DO NOTHING;
COMMIT;
