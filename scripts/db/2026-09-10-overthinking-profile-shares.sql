-- Additive listener profile publications. Safe to rerun; existing content is never rewritten.
-- Uses short bounded locks; deploy before exposing the new endpoints. No database reset.
BEGIN;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '60s';

CREATE TABLE IF NOT EXISTS soundconnect_schema_migrations (
    migration_id varchar(160) PRIMARY KEY,
    applied_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS tbl_overthinking_profile_share (
    id uuid PRIMARY KEY,
    owner_user_id uuid NOT NULL,
    listener_profile_id uuid NOT NULL,
    source_post_id uuid NOT NULL,
    note varchar(500),
    published_at timestamptz NOT NULL
);

-- Hibernate-created local tables get the same constraints as a fresh deployment.
-- No repair or cleanup is performed: unexpected existing invalid rows fail safely.
DO $migration$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='tbl_overthinking_profile_share'::regclass
                   AND conname='uq_overthinking_profile_share_owner_post') THEN
        ALTER TABLE tbl_overthinking_profile_share ADD CONSTRAINT uq_overthinking_profile_share_owner_post
            UNIQUE(owner_user_id, source_post_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='tbl_overthinking_profile_share'::regclass
                   AND conname='fk_overthinking_profile_share_owner') THEN
        ALTER TABLE tbl_overthinking_profile_share ADD CONSTRAINT fk_overthinking_profile_share_owner
            FOREIGN KEY(owner_user_id) REFERENCES tbl_user(id) ON DELETE CASCADE;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='tbl_overthinking_profile_share'::regclass
                   AND conname='fk_overthinking_profile_share_listener') THEN
        ALTER TABLE tbl_overthinking_profile_share ADD CONSTRAINT fk_overthinking_profile_share_listener
            FOREIGN KEY(listener_profile_id) REFERENCES "tbl_listener-profile"(id) ON DELETE CASCADE;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='tbl_overthinking_profile_share'::regclass
                   AND conname='fk_overthinking_profile_share_source') THEN
        ALTER TABLE tbl_overthinking_profile_share ADD CONSTRAINT fk_overthinking_profile_share_source
            FOREIGN KEY(source_post_id) REFERENCES tbl_overthinking_post(id) ON DELETE CASCADE;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='tbl_overthinking_profile_share'::regclass
                   AND conname='ck_overthinking_profile_share_note') THEN
        ALTER TABLE tbl_overthinking_profile_share ADD CONSTRAINT ck_overthinking_profile_share_note
            CHECK(note IS NULL OR (char_length(note) BETWEEN 1 AND 500 AND btrim(note)<>''));
    END IF;
END
$migration$;

CREATE INDEX IF NOT EXISTS ix_overthinking_profile_share_profile_page
    ON tbl_overthinking_profile_share(listener_profile_id, owner_user_id, published_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS ix_overthinking_profile_share_source ON tbl_overthinking_profile_share(source_post_id);
INSERT INTO soundconnect_schema_migrations(migration_id) VALUES('2026-09-10-overthinking-profile-shares')
    ON CONFLICT DO NOTHING;
COMMIT;
