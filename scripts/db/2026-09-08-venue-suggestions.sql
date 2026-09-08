-- Additive guest venue suggestion intake and per-recipient durable mail delivery.
-- Run against the intended SoundConnect PostgreSQL database after backup/review.
-- No existing application data is altered. This script is safe to run again.
BEGIN;
CREATE TABLE IF NOT EXISTS tbl_venue_suggestion (
    id uuid PRIMARY KEY,
    venue_name varchar(100) NOT NULL CHECK (char_length(venue_name) BETWEEN 2 AND 100),
    city_id uuid NOT NULL,
    district_id uuid NOT NULL,
    city_name varchar(255) NOT NULL,
    district_name varchar(255) NOT NULL,
    live_music varchar(10) NOT NULL CHECK (live_music IN ('YES','NO','UNKNOWN')),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS tbl_venue_suggestion_request (
    request_id uuid PRIMARY KEY,
    payload_hash varchar(64) NOT NULL CHECK (char_length(payload_hash) = 64),
    suggestion_id uuid REFERENCES tbl_venue_suggestion(id),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS tbl_venue_suggestion_dedupe (
    dedupe_key varchar(64) PRIMARY KEY CHECK (char_length(dedupe_key) = 64),
    suggestion_id uuid REFERENCES tbl_venue_suggestion(id),
    expires_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS tbl_venue_suggestion_mail (
    id uuid PRIMARY KEY,
    suggestion_id uuid NOT NULL REFERENCES tbl_venue_suggestion(id),
    recipient varchar(254) NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING','PUBLISHING','QUEUED','SENDING','SENT','NEEDS_REVIEW')),
    lease_token uuid,
    lease_until timestamptz,
    next_attempt_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    publish_attempts integer NOT NULL DEFAULT 0 CHECK (publish_attempts >= 0),
    send_attempts integer NOT NULL DEFAULT 0 CHECK (send_attempts >= 0),
    last_error varchar(100),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at timestamptz,
    CONSTRAINT uq_venue_suggestion_recipient UNIQUE (suggestion_id,recipient)
);
CREATE INDEX IF NOT EXISTS idx_venue_suggestion_mail_due
    ON tbl_venue_suggestion_mail (next_attempt_at,created_at,id) WHERE status IN ('PENDING','QUEUED');
CREATE INDEX IF NOT EXISTS idx_venue_suggestion_mail_lease
    ON tbl_venue_suggestion_mail (lease_until,id) WHERE status IN ('PUBLISHING','SENDING');
CREATE INDEX IF NOT EXISTS idx_venue_suggestion_mail_health
    ON tbl_venue_suggestion_mail (status,created_at) WHERE status<>'SENT';
COMMIT;
