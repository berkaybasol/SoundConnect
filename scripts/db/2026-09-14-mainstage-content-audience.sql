-- Content destination is independent of storage/privacy visibility.
-- Preserve existing music/performance: free text cannot reliably identify business intent.
-- Studio media is structurally Backstage; Collab has no media owner/attachment schema.
SET lock_timeout = '5s';
SET statement_timeout = '5min';

BEGIN;
ALTER TABLE public.tbl_media_asset
    ADD COLUMN IF NOT EXISTS content_audience varchar(16) NOT NULL DEFAULT 'MAINSTAGE';
UPDATE public.tbl_media_asset SET content_audience='MAINSTAGE' WHERE content_audience IS NULL;
UPDATE public.tbl_media_asset SET content_audience='BACKSTAGE'
    WHERE owner_type='STUDIO_PROFILE' AND content_audience<>'BACKSTAGE';
ALTER TABLE public.tbl_media_asset ALTER COLUMN content_audience SET DEFAULT 'MAINSTAGE';
ALTER TABLE public.tbl_media_asset ALTER COLUMN content_audience SET NOT NULL;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='public.tbl_media_asset'::regclass
                   AND conname='ck_media_content_audience') THEN
        ALTER TABLE public.tbl_media_asset ADD CONSTRAINT ck_media_content_audience
            CHECK (content_audience IN ('MAINSTAGE','BACKSTAGE'));
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS public.soundconnect_schema_migrations (
    migration_id varchar(160) PRIMARY KEY,
    applied_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO public.soundconnect_schema_migrations(migration_id)
VALUES ('2026-09-14-mainstage-content-audience') ON CONFLICT DO NOTHING;
COMMIT;

RESET statement_timeout;
RESET lock_timeout;
