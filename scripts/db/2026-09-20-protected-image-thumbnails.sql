-- Additive rollout: apply before deploying API/worker binaries with this field.
-- Existing originals remain readable while the bounded image worker backfills.
BEGIN;
SET LOCAL lock_timeout='5s';
SET LOCAL statement_timeout='60s';

ALTER TABLE public.tbl_media_asset
    ADD COLUMN IF NOT EXISTS thumbnail_storage_key varchar(512);

DO $protected_thumbnail_constraint$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conrelid='public.tbl_media_asset'::regclass
                     AND conname='ck_media_protected_thumbnail') THEN
        ALTER TABLE public.tbl_media_asset ADD CONSTRAINT ck_media_protected_thumbnail CHECK (
            thumbnail_storage_key IS NULL OR (
                kind='IMAGE' AND visibility='PRIVATE'
                AND storage_key IS NOT NULL
                AND storage_key LIKE 'protected/private-verified/%'
                AND thumbnail_storage_key=regexp_replace(storage_key,'[^/]+$','thumbnail.jpg')
                AND thumbnail_url IS NULL
            )
        );
    END IF;
END $protected_thumbnail_constraint$;

-- Match the worker's bounded pending set without scanning completed assets.
CREATE INDEX IF NOT EXISTS ix_media_missing_image_variant
    ON public.tbl_media_asset(kind,status,created_at,id)
    WHERE storage_key IS NOT NULL AND (
        (visibility='PUBLIC' AND (thumbnail_url IS NULL OR trim(thumbnail_url)=''))
        OR (visibility='PRIVATE' AND storage_key LIKE 'protected/private-verified/%'
            AND thumbnail_storage_key IS NULL)
    );
COMMIT;
