-- A suppressed anonymous request remains a normal pending receipt for its sender.
-- The recipient's inbox watermark must never advance for those requests.
BEGIN;
SET LOCAL lock_timeout = '10s';
ALTER TABLE tbl_overthinking_reveal_request
    ADD COLUMN IF NOT EXISTS delivery_suppressed boolean NOT NULL DEFAULT false,
    ALTER COLUMN inbox_revision DROP NOT NULL;
ALTER TABLE tbl_overthinking_reveal_request DROP CONSTRAINT IF EXISTS ck_overthinking_reveal_inbox_revision;
ALTER TABLE tbl_overthinking_reveal_request ADD CONSTRAINT ck_overthinking_reveal_inbox_revision
    CHECK ((delivery_suppressed AND inbox_revision IS NULL)
        OR (NOT delivery_suppressed AND inbox_revision IS NOT NULL AND inbox_revision > 0));
CREATE OR REPLACE TRIGGER trg_assign_overthinking_reveal_inbox_revision
BEFORE INSERT ON tbl_overthinking_reveal_request
FOR EACH ROW WHEN (NOT NEW.delivery_suppressed) EXECUTE FUNCTION assign_overthinking_reveal_inbox_revision();

INSERT INTO soundconnect_schema_migrations(migration_id)
VALUES ('2026-11-24-overthinking-block-delivery') ON CONFLICT DO NOTHING;
COMMIT;
