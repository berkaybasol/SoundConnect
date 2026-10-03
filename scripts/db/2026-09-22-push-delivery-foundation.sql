-- Additive migration. Apply explicitly before enabling app.notification.push.enabled.
-- No application startup runs this script and no provider is contacted here.
BEGIN;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '60s';

CREATE TABLE IF NOT EXISTS tbl_push_device (
    installation_id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES tbl_user(id) ON DELETE CASCADE,
    token_hash varchar(64) UNIQUE,
    token_ciphertext text,
    generation bigint NOT NULL DEFAULT 1 CHECK (generation > 0),
    platform varchar(16) NOT NULL CHECK (platform IN ('ANDROID','IOS')),
    permission varchar(24) NOT NULL CHECK (permission IN ('AUTHORIZED','PROVISIONAL','DENIED','NOT_DETERMINED')),
    app_version varchar(80),
    revoked_at timestamptz,
    last_seen_at timestamptz NOT NULL,
    CONSTRAINT ck_push_device_token CHECK ((token_hash IS NULL) = (token_ciphertext IS NULL))
);
CREATE INDEX IF NOT EXISTS idx_push_device_user ON tbl_push_device(user_id, revoked_at, last_seen_at);

CREATE TABLE IF NOT EXISTS tbl_push_preference (
    user_id uuid PRIMARY KEY REFERENCES tbl_user(id) ON DELETE CASCADE,
    enabled boolean NOT NULL DEFAULT true,
    disabled_categories text[] NOT NULL DEFAULT '{}',
    updated_at timestamptz NOT NULL
);

CREATE TABLE IF NOT EXISTS tbl_push_delivery (
    id uuid PRIMARY KEY,
    notification_id uuid NOT NULL REFERENCES tbl_notification(id) ON DELETE CASCADE,
    recipient_id uuid NOT NULL REFERENCES tbl_user(id) ON DELETE CASCADE,
    installation_id uuid NOT NULL REFERENCES tbl_push_device(installation_id) ON DELETE CASCADE,
    device_generation bigint NOT NULL CHECK (device_generation > 0),
    status varchar(24) NOT NULL CHECK (status IN ('PENDING','IN_FLIGHT','ACCEPTED','SUPPRESSED','DEAD_LETTER')),
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    lease_owner uuid,
    lease_until timestamptz,
    last_error_code varchar(80),
    provider_message_id varchar(512),
    accepted_at timestamptz,
    finished_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT uk_push_delivery_notification_device UNIQUE (notification_id, installation_id),
    CONSTRAINT ck_push_delivery_lease CHECK (
        (status = 'IN_FLIGHT' AND lease_owner IS NOT NULL AND lease_until IS NOT NULL)
        OR (status <> 'IN_FLIGHT' AND lease_owner IS NULL AND lease_until IS NULL)),
    CONSTRAINT ck_push_delivery_accepted CHECK ((status = 'ACCEPTED') = (accepted_at IS NOT NULL)),
    CONSTRAINT ck_push_delivery_finished CHECK ((status IN ('ACCEPTED','SUPPRESSED','DEAD_LETTER')) = (finished_at IS NOT NULL))
);
CREATE INDEX IF NOT EXISTS idx_push_delivery_due ON tbl_push_delivery(status, next_attempt_at, created_at);
CREATE INDEX IF NOT EXISTS idx_push_delivery_lease ON tbl_push_delivery(status, lease_until);
CREATE INDEX IF NOT EXISTS idx_push_delivery_finished ON tbl_push_delivery(finished_at) WHERE finished_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_push_delivery_recipient ON tbl_push_delivery(recipient_id);

-- Soft account erasure must also erase encrypted device addresses and preferences.
CREATE OR REPLACE FUNCTION soundconnect_erase_push_data() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.erased_at IS NOT NULL AND OLD.erased_at IS NULL THEN
        DELETE FROM tbl_push_device WHERE user_id = NEW.id;
        DELETE FROM tbl_push_preference WHERE user_id = NEW.id;
    END IF;
    RETURN NEW;
END;
$$;
DROP TRIGGER IF EXISTS trg_erase_push_data ON tbl_user;
CREATE TRIGGER trg_erase_push_data AFTER UPDATE OF erased_at ON tbl_user
FOR EACH ROW EXECUTE FUNCTION soundconnect_erase_push_data();

CREATE TABLE IF NOT EXISTS soundconnect_schema_migrations (
    migration_id varchar(160) PRIMARY KEY,
    applied_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO soundconnect_schema_migrations(migration_id) VALUES ('2026-09-22-push-delivery-foundation') ON CONFLICT DO NOTHING;
COMMIT;
