-- Online feed read-path indexes for established, potentially large tables.
-- IMPORTANT: this migration MUST run with autocommit enabled. PostgreSQL rejects
-- CREATE INDEX CONCURRENTLY inside an explicit or framework-managed transaction.
SET lock_timeout = '5s';
SET statement_timeout = '30min';

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_feed_tracks_created
    ON public.tbl_tracks(created_at DESC,id DESC)
    INCLUDE(owner_type,owner_id,media_asset_id);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_feed_profile_media_created
    ON public.tbl_profile_media(created_at DESC,id DESC)
    INCLUDE(profile_type,profile_id,media_asset_id,role);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_feed_follow_activity_time
    ON public.tbl_follow((coalesce(followed_at,created_at)) DESC,id DESC)
    INCLUDE(follower_id,following_id);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_feed_like_actor_target_time
    ON public.tbl_like(user_id,target_type,created_at DESC,id DESC)
    INCLUDE(target_id);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_feed_comment_actor_target_time
    ON public.tbl_comment(user_id,target_type,created_at DESC,id DESC)
    INCLUDE(target_id)
    WHERE not is_deleted;

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_feed_event_audience_published
    ON public.tbl_event_audience_intent(published_at DESC,post_id DESC)
    INCLUDE(user_id,event_id)
    WHERE published_on_profile and post_id is not null;

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_feed_overthinking_share_published
    ON public.tbl_overthinking_profile_share(published_at DESC,id DESC)
    INCLUDE(owner_user_id,listener_profile_id,source_post_id);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_feed_tablegroup_share_published
    ON public.tbl_table_group_profile_share(published_at DESC,id DESC)
    INCLUDE(owner_user_id,listener_profile_id,table_group_id);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_feed_event_future
    ON public.tbl_event(event_date,start_time,id)
    INCLUDE(venue_id,musician_profile_id,band_id,created_at)
    WHERE event_origin='VENUE' and venue_calendar_approved;

-- Complements the delivery item unique key for provider anti-joins that suppress
-- a native/activity item after its underlying target has already been delivered.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_feed_delivery_session_target
    ON public.tbl_musician_feed_delivery(
        viewer_user_id,feed_session_id,target_type,target_id)
    INCLUDE(item_type,item_id);

-- Collab already owns status/city/instrument/publisher/expiry publication indexes
-- in 2026-08-11-collab-domain.sql; duplicating them would increase write cost.
CREATE TABLE IF NOT EXISTS public.soundconnect_schema_migrations (
    migration_id varchar(160) PRIMARY KEY,
    applied_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO public.soundconnect_schema_migrations(migration_id)
VALUES ('2026-09-11-musician-feed-indexes') ON CONFLICT DO NOTHING;

RESET statement_timeout;
RESET lock_timeout;
