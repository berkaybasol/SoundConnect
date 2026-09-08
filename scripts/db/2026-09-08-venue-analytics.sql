-- Additive only. Apply before enabling app.venue-analytics; never contains viewer IDs or credentials.
BEGIN;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '60s';
SET LOCAL search_path = public;
CREATE TABLE IF NOT EXISTS soundconnect_schema_migrations (
    migration_id varchar(160) PRIMARY KEY,
    applied_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS tbl_venue_analytics_state (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    tracking_started_at timestamptz
);
INSERT INTO tbl_venue_analytics_state(singleton) VALUES (true) ON CONFLICT DO NOTHING;
CREATE TABLE IF NOT EXISTS tbl_venue_analytics_receipt (
    observation_id uuid PRIMARY KEY,
    actor_hash bytea NOT NULL CHECK (octet_length(actor_hash) = 32),
    payload_hash bytea NOT NULL CHECK (octet_length(payload_hash) = 32),
    expires_at timestamptz NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_venue_analytics_receipt_expiry ON tbl_venue_analytics_receipt(expires_at);
CREATE TABLE IF NOT EXISTS tbl_venue_analytics_presence (
    venue_id uuid NOT NULL,
    metric_day date NOT NULL,
    metric_type varchar(24) NOT NULL CHECK (metric_type IN ('EVENT_IMPRESSION','EVENT_DETAIL_VIEW','VENUE_PROFILE_VIEW')),
    event_id uuid NOT NULL,
    source_event_id uuid NOT NULL,
    viewer_key bytea NOT NULL CHECK (octet_length(viewer_key) = 32),
    PRIMARY KEY (venue_id,metric_day,metric_type,event_id,source_event_id,viewer_key),
    CHECK ((metric_type = 'VENUE_PROFILE_VIEW' AND event_id = '00000000-0000-0000-0000-000000000000')
        OR (metric_type <> 'VENUE_PROFILE_VIEW' AND event_id <> '00000000-0000-0000-0000-000000000000'
            AND source_event_id = '00000000-0000-0000-0000-000000000000'))
);
CREATE INDEX IF NOT EXISTS idx_venue_analytics_presence_expiry ON tbl_venue_analytics_presence(metric_day);
CREATE INDEX IF NOT EXISTS idx_venue_analytics_presence_event ON tbl_venue_analytics_presence(venue_id,event_id,metric_day,metric_type)
    INCLUDE (viewer_key) WHERE event_id <> '00000000-0000-0000-0000-000000000000';
CREATE INDEX IF NOT EXISTS idx_venue_analytics_presence_source ON tbl_venue_analytics_presence(venue_id,source_event_id,metric_day)
    INCLUDE (viewer_key) WHERE metric_type = 'VENUE_PROFILE_VIEW' AND source_event_id <> '00000000-0000-0000-0000-000000000000';
CREATE TABLE IF NOT EXISTS tbl_venue_analytics_recent_detail (
    venue_id uuid NOT NULL,
    event_id uuid NOT NULL,
    viewer_key bytea NOT NULL CHECK (octet_length(viewer_key) = 32),
    valid_windows tstzmultirange NOT NULL,
    expires_at timestamptz NOT NULL,
    PRIMARY KEY (venue_id,event_id,viewer_key)
);
CREATE INDEX IF NOT EXISTS idx_venue_analytics_recent_detail_expiry ON tbl_venue_analytics_recent_detail(expires_at);
COMMENT ON TABLE tbl_venue_analytics_presence IS
    '90-day pseudonymous daily presence. Counts use period-wide DISTINCT viewer_key. Deliberately no event foreign key: numeric venue history survives event deletion; no titles retained.';
INSERT INTO soundconnect_schema_migrations(migration_id)
VALUES ('2026-09-08-venue-analytics') ON CONFLICT DO NOTHING;
COMMIT;
