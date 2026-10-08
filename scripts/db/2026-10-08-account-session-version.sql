-- Additive, repeatable rollout. Apply before enabling the new API on every node.
-- Legacy tokens without sessionVersion remain valid at zero until a reset.
-- Old API nodes do not enforce this claim: do not leave them serving traffic
-- after declaring password-reset session revocation active.
BEGIN;
ALTER TABLE tbl_user ADD COLUMN IF NOT EXISTS session_version bigint NOT NULL DEFAULT 0;
UPDATE tbl_user SET session_version = 0 WHERE session_version IS NULL;
ALTER TABLE tbl_user ALTER COLUMN session_version SET DEFAULT 0;
ALTER TABLE tbl_user ALTER COLUMN session_version SET NOT NULL;
COMMENT ON COLUMN tbl_user.session_version IS
    'Monotonic credential revision; missing JWT claim means zero, first reset revokes legacy tokens';
COMMIT;
