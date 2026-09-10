-- Additive migration; apply after listener-account-erasure and before deploying the API.
-- Safe to rerun against both Hibernate-created and migration-created tables.
BEGIN;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '60s';

CREATE TABLE IF NOT EXISTS soundconnect_schema_migrations (
    migration_id varchar(160) PRIMARY KEY,
    applied_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS tbl_table_group_profile_share (
    id uuid PRIMARY KEY,
    owner_user_id uuid NOT NULL,
    listener_profile_id uuid NOT NULL,
    table_group_id uuid NOT NULL,
    note varchar(500),
    published_at timestamptz NOT NULL
);

DO $constraints$
DECLARE item record;
BEGIN
    FOR item IN SELECT * FROM (VALUES
        ('uq_table_group_profile_share_owner_source', 'UNIQUE(owner_user_id,table_group_id)'),
        ('fk_table_group_profile_share_owner', 'FOREIGN KEY(owner_user_id) REFERENCES tbl_user(id) ON DELETE CASCADE'),
        ('fk_table_group_profile_share_listener', 'FOREIGN KEY(listener_profile_id) REFERENCES "tbl_listener-profile"(id) ON DELETE CASCADE'),
        ('fk_table_group_profile_share_source', 'FOREIGN KEY(table_group_id) REFERENCES tbl_table_group(id) ON DELETE CASCADE'),
        ('ck_table_group_profile_share_note', 'CHECK(note IS NULL OR (char_length(note) BETWEEN 1 AND 500 AND btrim(note)<>''''))')
    ) AS definitions(name, expression) LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_constraint
                       WHERE conrelid='tbl_table_group_profile_share'::regclass AND conname=item.name) THEN
            EXECUTE format('ALTER TABLE tbl_table_group_profile_share ADD CONSTRAINT %I %s',item.name,item.expression);
        END IF;
    END LOOP;
END
$constraints$;

CREATE INDEX IF NOT EXISTS ix_table_group_profile_share_profile_page
    ON tbl_table_group_profile_share(listener_profile_id,owner_user_id,published_at DESC,id DESC);
CREATE INDEX IF NOT EXISTS ix_table_group_profile_share_source ON tbl_table_group_profile_share(table_group_id);

-- Widen only the existing Hibernate enum constraint, preserving unrelated checks
-- and all accepted targets, including installations with prior enum expansions.
DO $target_enum$
DECLARE existing_check record;
BEGIN
    FOR existing_check IN
        SELECT c.conrelid::regclass AS relation,c.conname,c.convalidated,pg_get_expr(c.conbin,c.conrelid) AS expression
        FROM pg_constraint c JOIN pg_attribute a ON a.attrelid=c.conrelid AND a.attname='target_type'
        WHERE c.conrelid IN ('tbl_comment'::regclass,'tbl_like'::regclass) AND c.contype='c'
          AND c.conkey=ARRAY[a.attnum]::smallint[]
          AND pg_get_expr(c.conbin,c.conrelid) LIKE '%ANY%ARRAY%'
          AND pg_get_expr(c.conbin,c.conrelid) LIKE '%''MEDIA''%'
          AND pg_get_expr(c.conbin,c.conrelid) LIKE '%''EVENT''%'
          AND pg_get_expr(c.conbin,c.conrelid) LIKE '%''OVERTHINKING''%'
          AND pg_get_expr(c.conbin,c.conrelid) NOT LIKE '%''TABLE_GROUP_POST''%'
    LOOP
        EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I',existing_check.relation,existing_check.conname);
        EXECUTE format('ALTER TABLE %s ADD CONSTRAINT %I CHECK ((%s) OR target_type=''TABLE_GROUP_POST'') NOT VALID',
                       existing_check.relation,existing_check.conname,existing_check.expression);
        IF existing_check.convalidated THEN
            EXECUTE format('ALTER TABLE %s VALIDATE CONSTRAINT %I',existing_check.relation,existing_check.conname);
        END IF;
    END LOOP;
END
$target_enum$;

-- Publication engagement must never survive a deletion/cascade or be inherited
-- by a subsequent publication of the same table. Replies precede roots.
CREATE OR REPLACE FUNCTION soundconnect_purge_table_profile_share() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    DELETE FROM tbl_like WHERE (target_type='TABLE_GROUP_POST' AND target_id=OLD.id)
        OR (target_type='COMMENT' AND target_id IN
            (SELECT id FROM tbl_comment WHERE target_type='TABLE_GROUP_POST' AND target_id=OLD.id));
    DELETE FROM tbl_comment WHERE target_type='TABLE_GROUP_POST' AND target_id=OLD.id AND parent_comment_id IS NOT NULL;
    DELETE FROM tbl_comment WHERE target_type='TABLE_GROUP_POST' AND target_id=OLD.id;
    RETURN OLD;
END $$;
DROP TRIGGER IF EXISTS tr_purge_table_profile_share ON tbl_table_group_profile_share;
CREATE TRIGGER tr_purge_table_profile_share BEFORE DELETE ON tbl_table_group_profile_share
FOR EACH ROW EXECUTE FUNCTION soundconnect_purge_table_profile_share();

-- Reuse the account-erasure write fence; deployment order is intentional.
DROP TRIGGER IF EXISTS tr_reject_erased_reference ON tbl_table_group_profile_share;
CREATE TRIGGER tr_reject_erased_reference BEFORE INSERT OR UPDATE OF owner_user_id ON tbl_table_group_profile_share
FOR EACH ROW EXECUTE FUNCTION soundconnect_reject_erased_reference('owner_user_id');

INSERT INTO soundconnect_schema_migrations(migration_id) VALUES('2026-09-10-tablegroup-profile-shares')
    ON CONFLICT DO NOTHING;
COMMIT;
