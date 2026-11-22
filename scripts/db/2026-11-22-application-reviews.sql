-- Apply before deploying the API with ddl-auto=validate. Historical decisions can
-- lack a reviewer or an explanation. Do not guess either from a current profile.
-- A completed row is its decision record for the lifetime of that application.
-- The successful admin response's X-Decision-Id identifies this same record.
-- The reviewer is panel-only. Only the applicant and authorized reviewers read
-- the explanation. Phone stays on the application and is not copied into audit.
-- These nullable, additive columns can stay in place if the API is rolled back.
BEGIN;

ALTER TABLE tbl_venue_applications
    ADD COLUMN IF NOT EXISTS decision_id uuid,
    ADD COLUMN IF NOT EXISTS reviewed_by_id uuid,
    ADD COLUMN IF NOT EXISTS rejection_reason varchar(500);
ALTER TABLE tbl_studio_applications
    ADD COLUMN IF NOT EXISTS decision_id uuid,
    ADD COLUMN IF NOT EXISTS approved_studio_profile_id uuid;

-- Store identifiers of the exact decision actor and created profile even when a
-- related account is eventually erased. All new values come from the authenticated
-- request or the result of provisioning in the same transaction.
CREATE INDEX IF NOT EXISTS idx_studio_application_rejected_by_applicant
    ON tbl_studio_applications (applicant_id, decision_date DESC, id DESC)
    WHERE status = 'REJECTED';

COMMIT;
