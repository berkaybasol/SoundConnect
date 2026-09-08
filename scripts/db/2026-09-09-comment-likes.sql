-- Apply only after backup/operator approval, before enabling the COMMENT-like binary.
-- No new table or data rewrite. Existing media/event/overthinking likes are preserved.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

DO $comment_like_preflight$
BEGIN
    IF to_regclass('tbl_like') IS NULL THEN
        RAISE EXCEPTION 'Comment likes require the existing tbl_like table';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_index i
        WHERE i.indrelid='tbl_like'::regclass AND i.indisunique AND i.indisvalid AND i.indimmediate
          AND i.indpred IS NULL AND i.indexprs IS NULL AND i.indnkeyatts=3
          AND (SELECT array_agg(a.attname::text ORDER BY k.ordinality)
               FROM unnest(i.indkey) WITH ORDINALITY k(attnum,ordinality)
               JOIN pg_attribute a ON a.attrelid=i.indrelid AND a.attnum=k.attnum
               WHERE k.ordinality<=i.indnkeyatts)=ARRAY['user_id','target_type','target_id']
    ) THEN
        RAISE EXCEPTION 'Comment likes require the existing unique user/type/target index; inspect legacy schema first';
    END IF;
END
$comment_like_preflight$;

-- Hibernate-generated names vary. Widen only recognized single-column enum checks;
-- retain their original expression (including old accepted values) and validation state.
-- Unrelated/multi-column checks and the tbl_comment target-type contract are untouched.
DO $comment_like_enum$
DECLARE existing_check record;
BEGIN
    FOR existing_check IN
        SELECT c.conname,c.convalidated,pg_get_expr(c.conbin,c.conrelid) AS expression
        FROM pg_constraint c JOIN pg_attribute a ON a.attrelid=c.conrelid AND a.attname='target_type'
        WHERE c.conrelid='tbl_like'::regclass AND c.contype='c'
          AND c.conkey=ARRAY[a.attnum]::smallint[]
          AND pg_get_expr(c.conbin,c.conrelid) LIKE '%ANY%ARRAY%'
          AND pg_get_expr(c.conbin,c.conrelid) LIKE '%''MEDIA''%'
          AND pg_get_expr(c.conbin,c.conrelid) LIKE '%''EVENT''%'
          AND pg_get_expr(c.conbin,c.conrelid) LIKE '%''OVERTHINKING''%'
          AND pg_get_expr(c.conbin,c.conrelid) NOT LIKE '%''COMMENT''%'
    LOOP
        EXECUTE format('ALTER TABLE tbl_like DROP CONSTRAINT %I',existing_check.conname);
        EXECUTE format('ALTER TABLE tbl_like ADD CONSTRAINT %I CHECK ((%s) OR target_type=''COMMENT'') NOT VALID',
                       existing_check.conname,existing_check.expression);
        IF existing_check.convalidated THEN
            EXECUTE format('ALTER TABLE tbl_like VALIDATE CONSTRAINT %I',existing_check.conname);
        END IF;
    END LOOP;
END
$comment_like_enum$;

CREATE INDEX IF NOT EXISTS idx_like_target ON tbl_like(target_type,target_id);
DO $comment_like_index$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_index i WHERE i.indexrelid=to_regclass('idx_like_target')
          AND i.indrelid='tbl_like'::regclass AND i.indisvalid AND i.indpred IS NULL
          AND i.indexprs IS NULL AND i.indnkeyatts=2
          AND (SELECT array_agg(a.attname::text ORDER BY k.ordinality)
               FROM unnest(i.indkey) WITH ORDINALITY k(attnum,ordinality)
               JOIN pg_attribute a ON a.attrelid=i.indrelid AND a.attnum=k.attnum
               WHERE k.ordinality<=i.indnkeyatts)=ARRAY['target_type','target_id']
    ) THEN RAISE EXCEPTION 'idx_like_target exists with an incompatible definition'; END IF;
END
$comment_like_index$;
COMMIT;
