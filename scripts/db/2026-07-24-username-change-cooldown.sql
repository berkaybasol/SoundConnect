-- SoundConnect self-service username change cooldown rollout.
--
-- Existing users intentionally keep NULL so their first username change is
-- immediately available. The application writes UTC wall-clock values,
-- matching the project's existing LocalDateTime audit-column convention.
--
-- This project intentionally has no automatic migration runner. Reconcile
-- against the production schema and run psql with ON_ERROR_STOP=1.

BEGIN;

ALTER TABLE tbl_user
	ADD COLUMN IF NOT EXISTS username_changed_at timestamp without time zone;

COMMENT ON COLUMN tbl_user.username_changed_at IS
	'UTC timestamp of the last successful self-service username change; NULL means no change consumed';

COMMIT;
