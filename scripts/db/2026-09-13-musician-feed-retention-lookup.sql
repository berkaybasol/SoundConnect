-- Bound the child-row lookups performed by delivery retention's ON DELETE SET NULL.
-- Prerequisites: 2026-09-11-musician-feed-delivery.sql and
-- 2026-09-11-musician-feed-feedback.sql. Run with ON_ERROR_STOP=1.
-- Uses regular transactional indexes with bounded deployment waits; no data is removed.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

CREATE INDEX IF NOT EXISTS idx_musician_feed_report_delivery_lookup
    ON public.tbl_musician_feed_content_report(delivery_id);
CREATE INDEX IF NOT EXISTS idx_musician_feed_feedback_delivery_lookup
    ON public.tbl_musician_feed_feedback(delivery_id);

CREATE TABLE IF NOT EXISTS public.soundconnect_schema_migrations (
    migration_id varchar(160) PRIMARY KEY,
    applied_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO public.soundconnect_schema_migrations(migration_id)
VALUES ('2026-09-13-musician-feed-retention-lookup') ON CONFLICT DO NOTHING;
COMMIT;
