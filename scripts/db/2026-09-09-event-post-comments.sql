-- Independent listener event publications. Run explicitly before the updated backend.
-- Existing EVENT comments stay on the event; no conversation is copied or reassigned.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

ALTER TABLE public.tbl_event_audience_intent ADD COLUMN IF NOT EXISTS post_id uuid;
UPDATE public.tbl_event_audience_intent SET post_id=gen_random_uuid()
WHERE published_on_profile AND post_id IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS ux_event_audience_post_id ON public.tbl_event_audience_intent(post_id);
DO $publication_index$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_index i
        JOIN pg_attribute a ON a.attrelid=i.indrelid AND a.attname='post_id'
        WHERE i.indexrelid='public.ux_event_audience_post_id'::regclass
          AND i.indrelid='public.tbl_event_audience_intent'::regclass
          AND i.indisunique AND i.indisvalid AND i.indimmediate
          AND i.indpred IS NULL AND i.indexprs IS NULL AND i.indnkeyatts=1 AND i.indkey[0]=a.attnum
    ) THEN RAISE EXCEPTION 'ux_event_audience_post_id has an incompatible definition'; END IF;
END
$publication_index$;

DO $publication_constraint$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='public.tbl_event_audience_intent'::regclass
                   AND conname='ck_event_audience_post_id') THEN
        ALTER TABLE public.tbl_event_audience_intent ADD CONSTRAINT ck_event_audience_post_id
            CHECK (published_on_profile = (post_id IS NOT NULL));
    END IF;
END
$publication_constraint$;

-- Preserve all existing accepted target types and unrelated checks. Hibernate
-- check names differ across installations, so widen only recognized enum checks.
DO $event_post_enum$
DECLARE existing_check record;
BEGIN
    FOR existing_check IN
        SELECT c.conrelid::regclass AS relation,c.conname,c.convalidated,pg_get_expr(c.conbin,c.conrelid) AS expression
        FROM pg_constraint c JOIN pg_attribute a ON a.attrelid=c.conrelid AND a.attname='target_type'
        WHERE c.conrelid IN ('public.tbl_comment'::regclass,'public.tbl_like'::regclass) AND c.contype='c'
          AND c.conkey=ARRAY[a.attnum]::smallint[]
          AND pg_get_expr(c.conbin,c.conrelid) LIKE '%ANY%ARRAY%'
          AND pg_get_expr(c.conbin,c.conrelid) LIKE '%''MEDIA''%'
          AND pg_get_expr(c.conbin,c.conrelid) LIKE '%''EVENT''%'
          AND pg_get_expr(c.conbin,c.conrelid) LIKE '%''OVERTHINKING''%'
          AND pg_get_expr(c.conbin,c.conrelid) NOT LIKE '%''EVENT_POST''%'
    LOOP
        EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I',existing_check.relation,existing_check.conname);
        EXECUTE format('ALTER TABLE %s ADD CONSTRAINT %I CHECK ((%s) OR target_type=''EVENT_POST'') NOT VALID',
                       existing_check.relation,existing_check.conname,existing_check.expression);
        IF existing_check.convalidated THEN
            EXECUTE format('ALTER TABLE %s VALIDATE CONSTRAINT %I',existing_check.relation,existing_check.conname);
        END IF;
    END LOOP;
END
$event_post_enum$;
COMMIT;
