-- Personal audience plans and optional listener profile posts.
-- Additive only. Apply explicitly through the authorized migration workflow;
-- this file does not change performer consent, calendar publication or analytics.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

CREATE TABLE IF NOT EXISTS public.tbl_event_audience_intent (
    user_id uuid NOT NULL REFERENCES public.tbl_user(id) ON DELETE CASCADE,
    -- Deliberately not an event FK: a NONE tombstone prevents stale resurrection
    -- after event deletion. No event title/details are copied into this table.
    event_id uuid NOT NULL,
    intent varchar(16) NOT NULL DEFAULT 'NONE',
    published_on_profile boolean NOT NULL DEFAULT false,
    note varchar(500),
    version bigint NOT NULL DEFAULT 0,
    updated_at timestamptz,
    published_at timestamptz,
    PRIMARY KEY(user_id,event_id),
    CONSTRAINT ck_event_audience_intent_value CHECK (intent IN ('NONE','THINKING','GOING')),
    CONSTRAINT ck_event_audience_intent_version CHECK (version >= 0),
    CONSTRAINT ck_event_audience_intent_publication CHECK (
        (NOT published_on_profile AND note IS NULL AND published_at IS NULL)
        OR (published_on_profile AND intent <> 'NONE' AND published_at IS NOT NULL)),
    CONSTRAINT ck_event_audience_intent_note CHECK (note IS NULL OR (char_length(note) <= 500 AND btrim(note) <> ''))
);
CREATE INDEX IF NOT EXISTS ix_event_audience_private ON public.tbl_event_audience_intent(user_id,event_id) WHERE intent <> 'NONE';
CREATE INDEX IF NOT EXISTS ix_event_audience_posts ON public.tbl_event_audience_intent(user_id,published_at DESC,event_id) WHERE published_on_profile;
COMMIT;
