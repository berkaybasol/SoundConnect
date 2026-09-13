-- Ordered per-renderer SHOW_LESS and provider item lookups; no preference is removed.
-- Run after 2026-09-11-musician-feed-feedback.sql and before enabling the new reader.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

CREATE INDEX IF NOT EXISTS idx_musician_feed_feedback_ranking_lookup
    ON public.tbl_musician_feed_feedback(viewer_user_id, action, item_type, id);
CREATE INDEX IF NOT EXISTS idx_musician_feed_feedback_item_lookup
    ON public.tbl_musician_feed_feedback(viewer_user_id, item_id, action);

CREATE TABLE IF NOT EXISTS public.soundconnect_schema_migrations (
    migration_id varchar(160) PRIMARY KEY,
    applied_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO public.soundconnect_schema_migrations(migration_id)
VALUES ('2026-09-13-musician-feed-feedback-lookup') ON CONFLICT DO NOTHING;
COMMIT;
