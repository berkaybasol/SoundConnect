-- Additive, rerunnable. Apply before deploying the application-mail writer.
-- PUBLISHED means confirmed/routed to Rabbit; it is NOT provider/mailbox delivery.
CREATE TABLE IF NOT EXISTS tbl_application_mail_intent (
    id uuid PRIMARY KEY,
    application_id uuid NOT NULL,
    purpose varchar(40) NOT NULL CHECK (purpose IN ('VENUE_APPLICATION_ADMIN','STUDIO_APPLICATION_ADMIN','STUDIO_APPLICATION_DECISION')),
    decision varchar(12) NOT NULL CHECK (decision IN ('CREATED','APPROVED','REJECTED')),
    recipient varchar(320) NOT NULL CHECK (length(btrim(recipient)) > 0),
    payload jsonb NOT NULL CHECK (jsonb_typeof(payload) = 'object' AND octet_length(payload::text) <= 131072),
    status varchar(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','PUBLISHING','PUBLISHED','NEEDS_REVIEW')),
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0 AND attempt_count <= max_attempts),
    max_attempts integer NOT NULL CHECK (max_attempts BETWEEN 1 AND 20),
    next_attempt_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_token uuid,
    lease_until timestamptz,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at timestamptz,
    last_error varchar(48),
    CONSTRAINT application_mail_logical_identity UNIQUE (application_id,purpose,decision,recipient),
    CONSTRAINT application_mail_purpose_decision CHECK (
        (purpose='STUDIO_APPLICATION_DECISION' AND decision IN ('APPROVED','REJECTED')) OR
        (purpose IN ('VENUE_APPLICATION_ADMIN','STUDIO_APPLICATION_ADMIN') AND decision='CREATED')),
    CONSTRAINT application_mail_lease CHECK (
        (status='PUBLISHING' AND lease_token IS NOT NULL AND lease_until IS NOT NULL) OR
        (status<>'PUBLISHING' AND lease_token IS NULL AND lease_until IS NULL))
);
CREATE INDEX IF NOT EXISTS application_mail_due_idx ON tbl_application_mail_intent(next_attempt_at,created_at,id) WHERE status='PENDING';
CREATE INDEX IF NOT EXISTS application_mail_lease_idx ON tbl_application_mail_intent(lease_until,created_at,id) WHERE status='PUBLISHING';
CREATE INDEX IF NOT EXISTS application_mail_health_idx ON tbl_application_mail_intent(status,created_at) WHERE status<>'PUBLISHED';
