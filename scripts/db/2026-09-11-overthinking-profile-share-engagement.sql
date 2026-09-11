-- Additive engagement identity for listener Overthinking profile publications.
-- Run after 2026-09-10-overthinking-profile-shares.sql. Existing OVERTHINKING
-- likes/comments remain attached to their source posts and are never rewritten.
BEGIN;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '60s';

CREATE TABLE IF NOT EXISTS soundconnect_schema_migrations (
    migration_id varchar(160) PRIMARY KEY,
    applied_at timestamptz NOT NULL DEFAULT now()
);

-- Hibernate check names differ between installations. Widen only the enum
-- checks recognized by their accepted legacy values; preserve unrelated checks
-- and whether each existing constraint was validated.
DO $target_enum$
DECLARE existing_check record;
BEGIN
    FOR existing_check IN
        SELECT c.conrelid::regclass AS relation,c.conname,c.convalidated,
               pg_get_expr(c.conbin,c.conrelid) AS expression
        FROM pg_constraint c
        JOIN pg_attribute a ON a.attrelid=c.conrelid AND a.attname='target_type'
        WHERE c.conrelid IN ('tbl_comment'::regclass,'tbl_like'::regclass)
          AND c.contype='c'
          AND c.conkey=ARRAY[a.attnum]::smallint[]
          AND pg_get_expr(c.conbin,c.conrelid) LIKE '%ANY%ARRAY%'
          AND pg_get_expr(c.conbin,c.conrelid) LIKE '%''MEDIA''%'
          AND pg_get_expr(c.conbin,c.conrelid) LIKE '%''EVENT''%'
          AND pg_get_expr(c.conbin,c.conrelid) LIKE '%''OVERTHINKING''%'
          AND pg_get_expr(c.conbin,c.conrelid) NOT LIKE '%''OVERTHINKING_PROFILE_SHARE''%'
    LOOP
        EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I',
                       existing_check.relation,existing_check.conname);
        EXECUTE format(
            'ALTER TABLE %s ADD CONSTRAINT %I CHECK ((%s) OR target_type=''OVERTHINKING_PROFILE_SHARE'') NOT VALID',
            existing_check.relation,existing_check.conname,existing_check.expression);
        IF existing_check.convalidated THEN
            EXECUTE format('ALTER TABLE %s VALIDATE CONSTRAINT %I',
                           existing_check.relation,existing_check.conname);
        END IF;
    END LOOP;
END
$target_enum$;

-- Generic engagement rows cannot own foreign keys to heterogeneous targets.
-- Make publication deletion/cascade the ownership boundary instead. Replies
-- are removed before roots and source-post engagement is deliberately ignored.
CREATE OR REPLACE FUNCTION soundconnect_purge_overthinking_profile_share()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    DELETE FROM tbl_like WHERE
        (target_type='OVERTHINKING_PROFILE_SHARE' AND target_id=OLD.id)
        OR (target_type='COMMENT' AND target_id IN
            (SELECT id FROM tbl_comment
             WHERE target_type='OVERTHINKING_PROFILE_SHARE' AND target_id=OLD.id));
    DELETE FROM tbl_comment
        WHERE target_type='OVERTHINKING_PROFILE_SHARE'
          AND target_id=OLD.id AND parent_comment_id IS NOT NULL;
    DELETE FROM tbl_comment
        WHERE target_type='OVERTHINKING_PROFILE_SHARE' AND target_id=OLD.id;
    RETURN OLD;
END $$;

DROP TRIGGER IF EXISTS tr_purge_overthinking_profile_share
    ON tbl_overthinking_profile_share;
CREATE TRIGGER tr_purge_overthinking_profile_share
BEFORE DELETE ON tbl_overthinking_profile_share
FOR EACH ROW EXECUTE FUNCTION soundconnect_purge_overthinking_profile_share();

INSERT INTO soundconnect_schema_migrations(migration_id)
VALUES('2026-09-11-overthinking-profile-share-engagement')
ON CONFLICT DO NOTHING;
COMMIT;
