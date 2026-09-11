-- Signed feed delivery ledger, operational telemetry and generic moderation evidence.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

CREATE TABLE IF NOT EXISTS public.tbl_musician_feed_delivery (
    id uuid PRIMARY KEY,
    viewer_user_id uuid NOT NULL,
    feed_session_id uuid NOT NULL,
    item_id varchar(256) NOT NULL,
    item_type varchar(48) NOT NULL,
    feed_lane varchar(32) NOT NULL,
    target_type varchar(48) NOT NULL,
    target_id uuid NOT NULL,
    author_profile_type varchar(24),
    author_profile_id uuid,
    reason_code varchar(64),
    feedback_capabilities varchar(160) NOT NULL,
    schema_version integer NOT NULL,
    algorithm_version varchar(64) NOT NULL,
    absolute_position bigint NOT NULL,
    campaign_id uuid,
    evidence_json jsonb NOT NULL,
    delivered_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    purge_after timestamptz NOT NULL
);

CREATE TABLE IF NOT EXISTS public.tbl_musician_feed_telemetry_event (
    id uuid PRIMARY KEY,
    viewer_user_id uuid NOT NULL,
    client_event_id uuid NOT NULL,
    delivery_id uuid NOT NULL,
    event_type varchar(24) NOT NULL,
    client_occurred_at timestamptz,
    recorded_at timestamptz NOT NULL
);

CREATE TABLE IF NOT EXISTS public.tbl_musician_feed_content_report (
    id uuid PRIMARY KEY,
    viewer_user_id uuid NOT NULL,
    delivery_id uuid NOT NULL,
    item_id varchar(256) NOT NULL,
    item_type varchar(48) NOT NULL,
    target_type varchar(48) NOT NULL,
    target_id uuid NOT NULL,
    reason varchar(500),
    evidence_json jsonb NOT NULL,
    status varchar(24) NOT NULL,
    reported_at timestamptz NOT NULL
);

ALTER TABLE public.tbl_musician_feed_delivery
    ADD COLUMN IF NOT EXISTS evidence_json jsonb,
    ADD COLUMN IF NOT EXISTS purge_after timestamptz,
    ADD COLUMN IF NOT EXISTS feed_lane varchar(32);
ALTER TABLE public.tbl_musician_feed_content_report
    ADD COLUMN IF NOT EXISTS evidence_json jsonb,
    ALTER COLUMN delivery_id DROP NOT NULL;

-- Hibernate may have created an earlier entity shape before the operational
-- retention columns existed. Normalize it before enforcing entity parity.
UPDATE public.tbl_musician_feed_delivery
SET evidence_json = '{}'::jsonb
WHERE evidence_json IS NULL;
UPDATE public.tbl_musician_feed_delivery
SET purge_after = GREATEST(expires_at + interval '90 days', delivered_at + interval '90 days')
WHERE purge_after IS NULL;
UPDATE public.tbl_musician_feed_delivery
SET feed_lane = CASE
    WHEN item_type IN ('OVERTHINKING_PROFILE_SHARE','TABLEGROUP_PROFILE_SHARE') THEN 'MODULE_SHARE'
    WHEN item_type = 'PROFILE_COMPLETION' THEN 'SYSTEM'
    ELSE 'FOLLOWING'
END
WHERE feed_lane IS NULL;
UPDATE public.tbl_musician_feed_content_report
SET evidence_json = jsonb_build_object(
        'itemId', item_id,
        'itemType', item_type,
        'target', jsonb_build_object('type', target_type, 'id', target_id))
WHERE evidence_json IS NULL;

ALTER TABLE public.tbl_musician_feed_delivery
    ALTER COLUMN evidence_json SET NOT NULL,
    ALTER COLUMN purge_after SET NOT NULL,
    ALTER COLUMN feed_lane SET NOT NULL;
ALTER TABLE public.tbl_musician_feed_content_report
    ALTER COLUMN evidence_json SET NOT NULL;

DO $telemetry_shape$
BEGIN
    IF EXISTS (SELECT 1 FROM public.tbl_musician_feed_telemetry_event WHERE delivery_id IS NULL) THEN
        RAISE EXCEPTION 'musician-feed delivery rollout blocked: telemetry rows without delivery identity';
    END IF;
END
$telemetry_shape$;
ALTER TABLE public.tbl_musician_feed_telemetry_event
    ALTER COLUMN delivery_id SET NOT NULL;

-- V1 analytics is an impression-scoped state signal, not an unbounded clickstream.
-- Normalize any pre-constraint rows deterministically before enforcing one event
-- of each type per delivered impression.
WITH ranked_telemetry AS (
    SELECT id, row_number() OVER (
        PARTITION BY viewer_user_id,delivery_id,event_type
        ORDER BY recorded_at,id
    ) AS duplicate_rank
    FROM public.tbl_musician_feed_telemetry_event
)
DELETE FROM public.tbl_musician_feed_telemetry_event telemetry
USING ranked_telemetry ranked
WHERE telemetry.id=ranked.id AND ranked.duplicate_rank>1;

ALTER TABLE public.tbl_musician_feed_content_report
    DROP CONSTRAINT IF EXISTS fk_musician_feed_report_delivery;

DO $constraints$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='fk_musician_feed_delivery_viewer'
                    AND conrelid='public.tbl_musician_feed_delivery'::regclass) THEN
        ALTER TABLE public.tbl_musician_feed_delivery ADD CONSTRAINT fk_musician_feed_delivery_viewer
            FOREIGN KEY(viewer_user_id) REFERENCES public.tbl_user(id) ON DELETE CASCADE;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='uk_musician_feed_delivery_item'
                    AND conrelid='public.tbl_musician_feed_delivery'::regclass) THEN
        ALTER TABLE public.tbl_musician_feed_delivery ADD CONSTRAINT uk_musician_feed_delivery_item
            UNIQUE(viewer_user_id,feed_session_id,item_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='uk_musician_feed_delivery_position'
                    AND conrelid='public.tbl_musician_feed_delivery'::regclass) THEN
        ALTER TABLE public.tbl_musician_feed_delivery ADD CONSTRAINT uk_musician_feed_delivery_position
            UNIQUE(viewer_user_id,feed_session_id,absolute_position);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='fk_musician_feed_telemetry_viewer'
                    AND conrelid='public.tbl_musician_feed_telemetry_event'::regclass) THEN
        ALTER TABLE public.tbl_musician_feed_telemetry_event ADD CONSTRAINT fk_musician_feed_telemetry_viewer
            FOREIGN KEY(viewer_user_id) REFERENCES public.tbl_user(id) ON DELETE CASCADE;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='fk_musician_feed_telemetry_delivery'
                    AND conrelid='public.tbl_musician_feed_telemetry_event'::regclass) THEN
        ALTER TABLE public.tbl_musician_feed_telemetry_event ADD CONSTRAINT fk_musician_feed_telemetry_delivery
            FOREIGN KEY(delivery_id) REFERENCES public.tbl_musician_feed_delivery(id) ON DELETE CASCADE;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='uk_musician_feed_telemetry_client'
                    AND conrelid='public.tbl_musician_feed_telemetry_event'::regclass) THEN
        ALTER TABLE public.tbl_musician_feed_telemetry_event ADD CONSTRAINT uk_musician_feed_telemetry_client
            UNIQUE(viewer_user_id,client_event_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='uk_musician_feed_telemetry_delivery_event'
                    AND conrelid='public.tbl_musician_feed_telemetry_event'::regclass) THEN
        ALTER TABLE public.tbl_musician_feed_telemetry_event ADD CONSTRAINT uk_musician_feed_telemetry_delivery_event
            UNIQUE(viewer_user_id,delivery_id,event_type);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='fk_musician_feed_report_viewer'
                    AND conrelid='public.tbl_musician_feed_content_report'::regclass) THEN
        ALTER TABLE public.tbl_musician_feed_content_report ADD CONSTRAINT fk_musician_feed_report_viewer
            FOREIGN KEY(viewer_user_id) REFERENCES public.tbl_user(id) ON DELETE CASCADE;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='fk_musician_feed_report_delivery'
                    AND conrelid='public.tbl_musician_feed_content_report'::regclass) THEN
        ALTER TABLE public.tbl_musician_feed_content_report ADD CONSTRAINT fk_musician_feed_report_delivery
            FOREIGN KEY(delivery_id) REFERENCES public.tbl_musician_feed_delivery(id) ON DELETE SET NULL;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='uk_musician_feed_report_delivery'
                    AND conrelid='public.tbl_musician_feed_content_report'::regclass) THEN
        ALTER TABLE public.tbl_musician_feed_content_report ADD CONSTRAINT uk_musician_feed_report_delivery
            UNIQUE(viewer_user_id,delivery_id);
    END IF;
END
$constraints$;

ALTER TABLE public.tbl_musician_feed_delivery
    DROP CONSTRAINT IF EXISTS ck_musician_feed_delivery_shape,
    ADD CONSTRAINT ck_musician_feed_delivery_shape CHECK (
        char_length(btrim(item_id))>0 AND char_length(btrim(item_type))>0
        AND feed_lane IN ('FOLLOWING','RELEVANT_OPPORTUNITY','GENERAL_DISCOVERY','MODULE_SHARE','SYSTEM')
        AND char_length(btrim(target_type))>0 AND schema_version>0 AND absolute_position>=0
        AND expires_at>delivered_at AND purge_after>=expires_at
        AND ((author_profile_type IS NULL AND author_profile_id IS NULL)
             OR (author_profile_type IN ('MUSICIAN','LISTENER','STUDIO','VENUE','BAND')
                 AND author_profile_id IS NOT NULL)));
ALTER TABLE public.tbl_musician_feed_telemetry_event
    DROP CONSTRAINT IF EXISTS ck_musician_feed_telemetry_type,
    ADD CONSTRAINT ck_musician_feed_telemetry_type CHECK (
        event_type IN ('IMPRESSION','OPEN','CTA','FOLLOW','SAVE','APPLY','HIDE','MUTE','REPORT'));
ALTER TABLE public.tbl_musician_feed_content_report
    DROP CONSTRAINT IF EXISTS ck_musician_feed_report_status,
    ADD CONSTRAINT ck_musician_feed_report_status CHECK (status IN ('NEW','REVIEWING','ACTIONED','DISMISSED')),
    DROP CONSTRAINT IF EXISTS ck_musician_feed_report_reason,
    ADD CONSTRAINT ck_musician_feed_report_reason CHECK (
        reason IS NULL OR (char_length(reason)<=500 AND btrim(reason)<>''));

CREATE INDEX IF NOT EXISTS idx_musician_feed_delivery_session
    ON public.tbl_musician_feed_delivery(viewer_user_id,feed_session_id,expires_at);
CREATE INDEX IF NOT EXISTS idx_musician_feed_delivery_campaign
    ON public.tbl_musician_feed_delivery(viewer_user_id,campaign_id,delivered_at DESC)
    WHERE campaign_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_musician_feed_delivery_expiry
    ON public.tbl_musician_feed_delivery(purge_after);
CREATE INDEX IF NOT EXISTS idx_musician_feed_telemetry_delivery
    ON public.tbl_musician_feed_telemetry_event(delivery_id,event_type,recorded_at DESC);
CREATE INDEX IF NOT EXISTS idx_musician_feed_telemetry_retention
    ON public.tbl_musician_feed_telemetry_event(recorded_at,id);
CREATE INDEX IF NOT EXISTS idx_musician_feed_report_queue
    ON public.tbl_musician_feed_content_report(status,reported_at DESC,id DESC);

CREATE TABLE IF NOT EXISTS public.soundconnect_schema_migrations (
    migration_id varchar(160) PRIMARY KEY,
    applied_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO public.soundconnect_schema_migrations(migration_id)
VALUES ('2026-09-11-musician-feed-delivery') ON CONFLICT DO NOTHING;
COMMIT;
