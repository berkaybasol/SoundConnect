-- Reuse the delivery-backed IMPRESSION stream for the last 24 hours of feed ranking.
-- Prerequisite: 2026-09-11-musician-feed-delivery.sql.
-- Run with autocommit and ON_ERROR_STOP=1; CONCURRENTLY cannot run inside a transaction.
-- Adds an index only. Does not insert, backfill or delete user/content/telemetry rows.
SET lock_timeout = '5s';
SET statement_timeout = '30min';

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_musician_feed_impression_viewer_time
    ON public.tbl_musician_feed_telemetry_event(viewer_user_id,recorded_at DESC)
    INCLUDE(delivery_id)
    WHERE event_type='IMPRESSION';

CREATE TABLE IF NOT EXISTS public.soundconnect_schema_migrations (
    migration_id varchar(160) PRIMARY KEY,
    applied_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO public.soundconnect_schema_migrations(migration_id)
VALUES ('2026-09-14-musician-feed-recent-views') ON CONFLICT DO NOTHING;

RESET statement_timeout;
RESET lock_timeout;
