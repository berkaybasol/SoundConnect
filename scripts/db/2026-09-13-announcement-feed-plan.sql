-- Feed plans are bounded signed cursor data; no new session table is required.
-- Normalize Hibernate's enum CHECK so an existing bootstrap database accepts
-- the new stable ANNOUNCEMENT feedback identity. Existing feedback is preserved.
-- Prerequisite: 2026-09-11-musician-feed-feedback.sql. Run with ON_ERROR_STOP=1.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

ALTER TABLE public.tbl_musician_feed_feedback
    DROP CONSTRAINT IF EXISTS tbl_musician_feed_feedback_item_type_check,
    ADD CONSTRAINT tbl_musician_feed_feedback_item_type_check CHECK (
        item_type IS NULL OR item_type IN (
            'TRACK','PROFILE_MEDIA','COLLAB','EVENT','EVENT_PROFILE_SHARE',
            'OVERTHINKING_PROFILE_SHARE','TABLEGROUP_PROFILE_SHARE','PROFILE',
            'ACTIVITY_FOLLOW','ACTIVITY_LIKE','ACTIVITY_COMMENT','PROFILE_COMPLETION',
            'SPONSORED','ANNOUNCEMENT'));

CREATE TABLE IF NOT EXISTS public.soundconnect_schema_migrations (
    migration_id varchar(160) PRIMARY KEY,
    applied_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO public.soundconnect_schema_migrations(migration_id)
VALUES ('2026-09-13-announcement-feed-plan') ON CONFLICT DO NOTHING;
COMMIT;
