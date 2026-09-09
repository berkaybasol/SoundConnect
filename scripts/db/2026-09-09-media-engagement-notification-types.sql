-- Apply before deploying SOCIAL_LIKE / SOCIAL_COMMENT producers.
-- Extend existing single-column enum CHECK constraints without replacing the
-- previously allowed notification types. No notification or mock data is edited.
BEGIN;
DO $$
DECLARE
    constraint_row record;
BEGIN
    FOR constraint_row IN
        SELECT c.conname, pg_get_expr(c.conbin, c.conrelid) AS expression
        FROM pg_constraint c
        JOIN pg_attribute a ON a.attrelid=c.conrelid AND a.attname='type'
        WHERE c.conrelid='tbl_notification'::regclass AND c.contype='c'
          AND c.conkey=ARRAY[a.attnum]::smallint[]
          AND (position('SOCIAL_LIKE' in pg_get_expr(c.conbin,c.conrelid))=0
               OR position('SOCIAL_COMMENT' in pg_get_expr(c.conbin,c.conrelid))=0)
    LOOP
        EXECUTE format('ALTER TABLE tbl_notification DROP CONSTRAINT %I', constraint_row.conname);
        EXECUTE format('ALTER TABLE tbl_notification ADD CONSTRAINT %I CHECK ((%s) OR type IN (''SOCIAL_LIKE'',''SOCIAL_COMMENT''))',
                       constraint_row.conname, constraint_row.expression);
    END LOOP;
END $$;
COMMIT;
