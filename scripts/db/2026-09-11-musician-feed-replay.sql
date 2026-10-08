-- Exact, bounded replay of atomically committed continuation pages.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

CREATE TABLE IF NOT EXISTS public.tbl_musician_feed_page_replay (
    id uuid PRIMARY KEY,
    viewer_user_id uuid NOT NULL,
    feed_session_id uuid NOT NULL,
    request_position bigint NOT NULL,
    request_fingerprint varchar(64) NOT NULL,
    requested_limit integer NOT NULL,
    supported_types varchar(768) NOT NULL,
    schema_version integer NOT NULL,
    algorithm_version varchar(64) NOT NULL,
    response_json text NOT NULL,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL
);

DO $constraints$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='fk_musician_feed_replay_viewer'
                    AND conrelid='public.tbl_musician_feed_page_replay'::regclass) THEN
        ALTER TABLE public.tbl_musician_feed_page_replay ADD CONSTRAINT fk_musician_feed_replay_viewer
            FOREIGN KEY(viewer_user_id) REFERENCES public.tbl_user(id) ON DELETE CASCADE;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='uk_musician_feed_replay_position'
                    AND conrelid='public.tbl_musician_feed_page_replay'::regclass) THEN
        ALTER TABLE public.tbl_musician_feed_page_replay ADD CONSTRAINT uk_musician_feed_replay_position
            UNIQUE(viewer_user_id,feed_session_id,request_position);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='uk_musician_feed_replay_fingerprint'
                    AND conrelid='public.tbl_musician_feed_page_replay'::regclass) THEN
        ALTER TABLE public.tbl_musician_feed_page_replay ADD CONSTRAINT uk_musician_feed_replay_fingerprint
            UNIQUE(viewer_user_id,feed_session_id,request_fingerprint);
    END IF;
END
$constraints$;

ALTER TABLE public.tbl_musician_feed_page_replay
    DROP CONSTRAINT IF EXISTS ck_musician_feed_replay_shape,
    ADD CONSTRAINT ck_musician_feed_replay_shape CHECK (
        request_position>=0 AND requested_limit BETWEEN 1 AND 100
        AND char_length(request_fingerprint)=43
        AND char_length(btrim(supported_types))>0
        AND schema_version>0 AND char_length(btrim(algorithm_version))>0
        AND jsonb_typeof(response_json::jsonb)='object'
        AND octet_length(response_json)<=4194304
        AND expires_at>created_at);

CREATE INDEX IF NOT EXISTS idx_musician_feed_replay_expiry
    ON public.tbl_musician_feed_page_replay(expires_at,id);

CREATE TABLE IF NOT EXISTS public.soundconnect_schema_migrations (
    migration_id varchar(160) PRIMARY KEY,
    applied_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO public.soundconnect_schema_migrations(migration_id)
VALUES ('2026-09-11-musician-feed-replay') ON CONFLICT DO NOTHING;
COMMIT;
