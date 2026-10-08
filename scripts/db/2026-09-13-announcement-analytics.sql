-- Extends the existing analytics receipt/queue pipeline; promotion is the content identity.
-- Apply after the original venue analytics and feed-announcements migrations.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

CREATE TABLE IF NOT EXISTS public.tbl_promotion_analytics_event (
    observation_id uuid PRIMARY KEY,
    promotion_id uuid NOT NULL REFERENCES public.tlb_promotion(id) ON DELETE CASCADE,
    actor_key bytea NOT NULL,
    viewer_key bytea NOT NULL,
    profile_type varchar(24) NOT NULL CHECK(profile_type IN ('MUSICIAN','LISTENER','VENUE','STUDIO')),
    source varchar(24) NOT NULL CHECK(source IN ('FEED','DIRECTORY')),
    metric_type varchar(40) NOT NULL CHECK(metric_type IN ('ANNOUNCEMENT_IMPRESSION','ANNOUNCEMENT_DETAIL_VIEW',
        'ANNOUNCEMENT_VIDEO_START','ANNOUNCEMENT_VIDEO_COMPLETE')),
    observed_at timestamptz NOT NULL,
    recorded_at timestamptz NOT NULL,
    playback_id uuid,
    delivery_id uuid,
    CHECK ((metric_type IN ('ANNOUNCEMENT_VIDEO_START','ANNOUNCEMENT_VIDEO_COMPLETE')) = (playback_id IS NOT NULL)),
    CHECK ((source='FEED') = (delivery_id IS NOT NULL))
);
-- Receipts remain in the existing analytics table. These natural keys also reject
-- a retry that incorrectly invents a second observation ID for the same exposure/play.
CREATE UNIQUE INDEX IF NOT EXISTS uk_promotion_analytics_feed_impression
    ON public.tbl_promotion_analytics_event(promotion_id,viewer_key,delivery_id)
    WHERE metric_type='ANNOUNCEMENT_IMPRESSION' AND source='FEED';
CREATE UNIQUE INDEX IF NOT EXISTS uk_promotion_analytics_playback
    ON public.tbl_promotion_analytics_event(promotion_id,viewer_key,playback_id,metric_type)
    WHERE playback_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_promotion_analytics_report
    ON public.tbl_promotion_analytics_event(promotion_id,observed_at,source,profile_type);
CREATE INDEX IF NOT EXISTS idx_promotion_analytics_actor_history
    ON public.tbl_promotion_analytics_event(actor_key,promotion_id,metric_type,recorded_at);

CREATE TABLE IF NOT EXISTS public.tbl_promotion_analytics_engagement (
    entity_id uuid NOT NULL,
    metric_type varchar(16) NOT NULL CHECK(metric_type IN ('LIKE','COMMENT','HIDE')),
    promotion_id uuid NOT NULL REFERENCES public.tlb_promotion(id) ON DELETE CASCADE,
    viewer_key bytea NOT NULL,
    profile_type varchar(24) NOT NULL CHECK(profile_type IN ('MUSICIAN','LISTENER','VENUE','STUDIO')),
    source varchar(24) NOT NULL CHECK(source IN ('FEED','DIRECTORY','UNATTRIBUTED')),
    occurred_at timestamptz NOT NULL,
    PRIMARY KEY(entity_id,metric_type)
);
CREATE INDEX IF NOT EXISTS idx_promotion_analytics_engagement_report
    ON public.tbl_promotion_analytics_engagement(promotion_id,occurred_at,source,profile_type);

CREATE TABLE IF NOT EXISTS public.tbl_promotion_analytics_state (
    singleton boolean PRIMARY KEY DEFAULT true CHECK(singleton),
    tracking_started_at timestamptz
);
INSERT INTO public.tbl_promotion_analytics_state(singleton) VALUES(true) ON CONFLICT DO NOTHING;
COMMIT;
