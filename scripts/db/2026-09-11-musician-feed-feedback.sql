-- Additive persistence for musician-feed hide/show-less/mute/report actions.
-- Every constraint is normalized for the local Hibernate-first bootstrap path.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

CREATE TABLE IF NOT EXISTS public.tbl_musician_feed_feedback (
    id uuid PRIMARY KEY,
    viewer_user_id uuid NOT NULL,
    action varchar(24) NOT NULL,
    scope_key varchar(320) NOT NULL,
    item_id varchar(256),
    item_type varchar(48),
    delivery_id uuid,
    author_profile_type varchar(24),
    author_profile_id uuid,
    reason varchar(500),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);

-- Hibernate ddl-auto=update can create the table before this script runs. Keep the
-- migration authoritative for relational constraints in that deployment order.
ALTER TABLE public.tbl_musician_feed_feedback
    ADD COLUMN IF NOT EXISTS delivery_id uuid,
    ADD COLUMN IF NOT EXISTS author_profile_type varchar(24),
    ADD COLUMN IF NOT EXISTS author_profile_id uuid;

ALTER TABLE public.tbl_musician_feed_feedback
    DROP CONSTRAINT IF EXISTS fk_musician_feed_feedback_delivery;

DO $migration$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'fk_musician_feed_feedback_viewer'
          AND conrelid = 'public.tbl_musician_feed_feedback'::regclass
    ) THEN
        ALTER TABLE public.tbl_musician_feed_feedback
            ADD CONSTRAINT fk_musician_feed_feedback_viewer
            FOREIGN KEY (viewer_user_id) REFERENCES public.tbl_user(id) ON DELETE CASCADE;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'fk_musician_feed_feedback_delivery'
          AND conrelid = 'public.tbl_musician_feed_feedback'::regclass
    ) THEN
        ALTER TABLE public.tbl_musician_feed_feedback
            ADD CONSTRAINT fk_musician_feed_feedback_delivery
            FOREIGN KEY (delivery_id) REFERENCES public.tbl_musician_feed_delivery(id) ON DELETE SET NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'uk_musician_feed_feedback_scope'
          AND conrelid = 'public.tbl_musician_feed_feedback'::regclass
    ) THEN
        ALTER TABLE public.tbl_musician_feed_feedback
            ADD CONSTRAINT uk_musician_feed_feedback_scope
            UNIQUE (viewer_user_id, action, scope_key);
    END IF;
END
$migration$;

ALTER TABLE public.tbl_musician_feed_feedback
    DROP CONSTRAINT IF EXISTS ck_musician_feed_feedback_action,
    ADD CONSTRAINT ck_musician_feed_feedback_action CHECK (
        action IN ('HIDE','SHOW_LESS','MUTE_AUTHOR','REPORT')),
    DROP CONSTRAINT IF EXISTS ck_musician_feed_feedback_reason,
    ADD CONSTRAINT ck_musician_feed_feedback_reason CHECK (
        reason IS NULL OR (char_length(reason) <= 500 AND btrim(reason) <> '')),
    DROP CONSTRAINT IF EXISTS ck_musician_feed_feedback_profile_type,
    ADD CONSTRAINT ck_musician_feed_feedback_profile_type CHECK (
        author_profile_type IS NULL OR author_profile_type IN
            ('MUSICIAN','LISTENER','STUDIO','VENUE','BAND')),
    DROP CONSTRAINT IF EXISTS ck_musician_feed_feedback_shape,
    ADD CONSTRAINT ck_musician_feed_feedback_shape CHECK (
        (action = 'MUTE_AUTHOR'
            AND author_profile_type IS NOT NULL AND author_profile_id IS NOT NULL
            AND item_id IS NULL AND item_type IS NULL AND delivery_id IS NULL)
        OR (action <> 'MUTE_AUTHOR'
            AND author_profile_type IS NULL AND author_profile_id IS NULL
            AND item_id IS NOT NULL AND item_type IS NOT NULL));

CREATE INDEX IF NOT EXISTS idx_musician_feed_feedback_viewer_action
    ON public.tbl_musician_feed_feedback(viewer_user_id, action, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_musician_feed_feedback_viewer_profile
    ON public.tbl_musician_feed_feedback(viewer_user_id, author_profile_type, author_profile_id)
    WHERE author_profile_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_musician_feed_feedback_viewer_item
    ON public.tbl_musician_feed_feedback(viewer_user_id, item_id)
    WHERE action IN ('HIDE','REPORT') AND item_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS public.soundconnect_schema_migrations (
    migration_id varchar(160) PRIMARY KEY,
    applied_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO public.soundconnect_schema_migrations(migration_id)
VALUES ('2026-09-11-musician-feed-feedback') ON CONFLICT DO NOTHING;
COMMIT;
