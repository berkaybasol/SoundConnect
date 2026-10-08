-- Explicit additive migration. No product/user/notification rows are modified.
BEGIN;
CREATE TABLE IF NOT EXISTS tbl_mobile_diagnostic_event (
    event_id uuid PRIMARY KEY,
    fingerprint varchar(64) NOT NULL,
    severity varchar(8) NOT NULL CHECK (severity IN ('ERROR','FATAL')),
    source varchar(32) NOT NULL CHECK (source IN ('FLUTTER_FRAMEWORK','UNHANDLED_ZONE','PLATFORM_DISPATCHER','FRAME_TIMING','DIAGNOSTICS_CHECK','BLOC','RECOVERABLE')),
    error_type varchar(40) NOT NULL,
    frames varchar(9640) NOT NULL,
    environment varchar(16) NOT NULL CHECK (environment IN ('local','staging','production')),
    received_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (fingerprint ~ '^[0-9a-f]{64}$')
);
CREATE INDEX IF NOT EXISTS idx_mobile_diagnostic_received ON tbl_mobile_diagnostic_event(received_at,event_id);
CREATE INDEX IF NOT EXISTS idx_mobile_diagnostic_environment_received ON tbl_mobile_diagnostic_event(environment,received_at);
COMMIT;
