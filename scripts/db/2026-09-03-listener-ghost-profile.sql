-- Listener ghost-profile visibility contract.
--
-- Run with psql -v ON_ERROR_STOP=1 while listener-profile and follow writers
-- are stopped. The repository intentionally has no automatic migration runner;
-- this additive, rerunnable script must be reconciled with the target PostgreSQL
-- schema and applied before deploying code that maps these non-null columns.

BEGIN;

DO $migration$
BEGIN
    IF to_regclass('"tbl_listener-profile"') IS NULL THEN
        RAISE EXCEPTION 'Listener ghost-profile migration requires "tbl_listener-profile"';
    END IF;

    IF to_regclass('tbl_follow') IS NULL THEN
        RAISE EXCEPTION 'Listener ghost-profile migration requires tbl_follow';
    END IF;
END
$migration$;

ALTER TABLE "tbl_listener-profile"
    ADD COLUMN IF NOT EXISTS visibility_mode varchar(16);

ALTER TABLE "tbl_listener-profile"
    ADD COLUMN IF NOT EXISTS visibility_changed_at timestamp without time zone;

ALTER TABLE "tbl_listener-profile"
    ADD COLUMN IF NOT EXISTS visibility_choice_completed boolean;

ALTER TABLE "tbl_listener-profile"
    ADD COLUMN IF NOT EXISTS version bigint;

-- IF NOT EXISTS must never silently bless a same-named but incompatible
-- column left by a partial/manual rollout.
DO $migration$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'tbl_listener-profile'
          AND column_name = 'visibility_mode'
          AND data_type = 'character varying'
          AND character_maximum_length = 16
    ) THEN
        RAISE EXCEPTION 'visibility_mode must be varchar(16)';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'tbl_listener-profile'
          AND column_name = 'visibility_changed_at'
          AND data_type = 'timestamp without time zone'
    ) THEN
        RAISE EXCEPTION 'visibility_changed_at must be timestamp without time zone';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'tbl_listener-profile'
          AND column_name = 'visibility_choice_completed'
          AND data_type = 'boolean'
    ) THEN
        RAISE EXCEPTION 'visibility_choice_completed must be boolean';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'tbl_listener-profile'
          AND column_name = 'version'
          AND data_type = 'bigint'
    ) THEN
        RAISE EXCEPTION 'listener profile version must be bigint';
    END IF;
END
$migration$;

-- Every legacy listener begins in the existing STANDARD behavior. Existing
-- profile content is preserved; relationship rows that violate the restricted
-- visibility invariant are removed below.
UPDATE "tbl_listener-profile"
SET visibility_mode = 'STANDARD'
WHERE visibility_mode IS NULL;

-- Rows that predate the chooser retain their historical STANDARD/GHOST state
-- and must never be forced through new-user onboarding. Once the column has a
-- false default, reruns leave genuinely incomplete new profiles untouched.
UPDATE "tbl_listener-profile"
SET visibility_choice_completed = true
WHERE visibility_choice_completed IS NULL;

UPDATE "tbl_listener-profile"
SET version = 0
WHERE version IS NULL;

DO $migration$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM "tbl_listener-profile"
        WHERE visibility_mode NOT IN ('STANDARD', 'GHOST')
    ) THEN
        RAISE EXCEPTION 'Listener profile contains unsupported visibility_mode values';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM "tbl_listener-profile"
        WHERE version < 0
    ) THEN
        RAISE EXCEPTION 'Listener profile contains negative version values';
    END IF;
END
$migration$;

ALTER TABLE "tbl_listener-profile"
    ALTER COLUMN visibility_mode SET DEFAULT 'STANDARD';

ALTER TABLE "tbl_listener-profile"
    ALTER COLUMN visibility_mode SET NOT NULL;

ALTER TABLE "tbl_listener-profile"
    ALTER COLUMN visibility_choice_completed SET DEFAULT false;

ALTER TABLE "tbl_listener-profile"
    ALTER COLUMN visibility_choice_completed SET NOT NULL;

ALTER TABLE "tbl_listener-profile"
    ALTER COLUMN version SET DEFAULT 0;

ALTER TABLE "tbl_listener-profile"
    ALTER COLUMN version SET NOT NULL;

-- Recreate same-named constraints deterministically. Merely checking the name
-- could preserve a weaker/manual definition and create a false-safe rollout.
ALTER TABLE "tbl_listener-profile"
    DROP CONSTRAINT IF EXISTS ck_listener_profile_visibility_mode;
ALTER TABLE "tbl_listener-profile"
    ADD CONSTRAINT ck_listener_profile_visibility_mode
    CHECK (visibility_mode IN ('STANDARD', 'GHOST')) NOT VALID;

ALTER TABLE "tbl_listener-profile"
    DROP CONSTRAINT IF EXISTS ck_listener_profile_version;
ALTER TABLE "tbl_listener-profile"
    ADD CONSTRAINT ck_listener_profile_version
    CHECK (version >= 0) NOT VALID;

ALTER TABLE "tbl_listener-profile"
    VALIDATE CONSTRAINT ck_listener_profile_visibility_mode;

ALTER TABLE "tbl_listener-profile"
    VALIDATE CONSTRAINT ck_listener_profile_version;

-- A restricted transition purges rows by following_id in the same transaction. This
-- index prevents that lifecycle operation from degrading into a full scan.
-- Recreate it deterministically: IF NOT EXISTS could silently accept a manual
-- same-named index over follower_id (or another incompatible definition).
DROP INDEX IF EXISTS idx_follow_following_id;
CREATE INDEX idx_follow_following_id
    ON tbl_follow (following_id);

-- Repair drift created by an old node or a partial rollout before installing
-- the database guards. Writers are stopped for this migration, so no new edge
-- can appear between this cleanup and trigger creation.
DELETE FROM tbl_follow AS follow_edge
USING "tbl_listener-profile" AS listener
WHERE follow_edge.following_id = listener.user_id
  AND (
      listener.visibility_mode = 'GHOST'
      OR listener.visibility_choice_completed IS NOT TRUE
  );

-- A listener role without its one-to-one profile is also onboarding-private.
-- This repairs legacy/partial provisioning drift before the trigger is replaced.
DELETE FROM tbl_follow AS follow_edge
USING user_roles AS user_role, tbl_role AS role
WHERE follow_edge.following_id = user_role.user_id
  AND user_role.role_id = role.id
  AND role.name = 'ROLE_LISTENER'
  AND NOT EXISTS (
      SELECT 1
      FROM "tbl_listener-profile" AS listener
      WHERE listener.user_id = follow_edge.following_id
        AND listener.visibility_choice_completed IS TRUE
  );

-- Database-level serialization protects the invariant even from an old node,
-- an import, or another writer that does not know the application lock
-- protocol. FOR SHARE conflicts with the visibility writer's FOR UPDATE lock:
-- the insert either commits first and is purged, or observes GHOST and fails.
CREATE OR REPLACE FUNCTION soundconnect_reject_follow_to_ghost_listener()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
DECLARE
    target_visibility varchar(16);
    target_choice_completed boolean;
BEGIN
    SELECT visibility_mode, visibility_choice_completed
      INTO target_visibility, target_choice_completed
      FROM "tbl_listener-profile"
     WHERE user_id = NEW.following_id
     FOR SHARE;

    IF FOUND THEN
        IF target_visibility = 'GHOST' OR target_choice_completed IS NOT TRUE THEN
            RAISE EXCEPTION USING
                ERRCODE = '23514',
                CONSTRAINT = 'ck_follow_target_not_ghost',
                MESSAGE = 'ghost or onboarding-private listener profiles cannot receive followers';
        END IF;
    ELSE
        -- A missing profile has no row to lock. Lock the target user as the same
        -- creation mutex used by the application provisioner, then re-read the
        -- profile after any concurrent repair/selection transaction completes.
        PERFORM 1 FROM tbl_user WHERE id = NEW.following_id FOR SHARE;

        SELECT visibility_mode, visibility_choice_completed
          INTO target_visibility, target_choice_completed
          FROM "tbl_listener-profile"
         WHERE user_id = NEW.following_id
         FOR SHARE;

        IF FOUND THEN
            IF target_visibility = 'GHOST' OR target_choice_completed IS NOT TRUE THEN
                RAISE EXCEPTION USING
                    ERRCODE = '23514',
                    CONSTRAINT = 'ck_follow_target_not_ghost',
                    MESSAGE = 'ghost or onboarding-private listener profiles cannot receive followers';
            END IF;
        ELSIF EXISTS (
            SELECT 1
            FROM user_roles AS user_role
            JOIN tbl_role AS role ON role.id = user_role.role_id
            WHERE user_role.user_id = NEW.following_id
              AND role.name = 'ROLE_LISTENER'
        ) THEN
            RAISE EXCEPTION USING
                ERRCODE = '23514',
                CONSTRAINT = 'ck_follow_target_not_ghost',
                MESSAGE = 'ghost or onboarding-private listener profiles cannot receive followers';
        END IF;
    END IF;
    RETURN NEW;
END
$function$;

DROP TRIGGER IF EXISTS trg_follow_reject_ghost_listener ON tbl_follow;
CREATE TRIGGER trg_follow_reject_ghost_listener
    BEFORE INSERT OR UPDATE OF following_id ON tbl_follow
    FOR EACH ROW
    EXECUTE FUNCTION soundconnect_reject_follow_to_ghost_listener();

-- The service performs this cleanup explicitly so it can report the count.
-- This trigger is a final invariant guard for direct/legacy visibility writes.
CREATE OR REPLACE FUNCTION soundconnect_purge_followers_for_ghost_listener()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
BEGIN
    IF (NEW.visibility_mode = 'GHOST' OR NEW.visibility_choice_completed IS NOT TRUE)
       AND (
           TG_OP = 'INSERT'
           OR (
               OLD.visibility_mode IS DISTINCT FROM 'GHOST'
               AND OLD.visibility_choice_completed IS TRUE
           )
       ) THEN
        DELETE FROM tbl_follow WHERE following_id = NEW.user_id;
    END IF;
    RETURN NEW;
END
$function$;

DROP TRIGGER IF EXISTS trg_listener_profile_purge_followers ON "tbl_listener-profile";
CREATE TRIGGER trg_listener_profile_purge_followers
    AFTER INSERT OR UPDATE OF visibility_mode, visibility_choice_completed
    ON "tbl_listener-profile"
    FOR EACH ROW
    EXECUTE FUNCTION soundconnect_purge_followers_for_ghost_listener();

COMMENT ON COLUMN "tbl_listener-profile".visibility_mode IS
    'Listener public showcase mode: STANDARD or GHOST; GHOST is not anonymity.';
COMMENT ON COLUMN "tbl_listener-profile".visibility_changed_at IS
    'UTC wall-clock time of the most recent effective visibility transition.';
COMMENT ON COLUMN "tbl_listener-profile".visibility_choice_completed IS
    'True after the listener explicitly chooses STANDARD or GHOST; legacy rows are backfilled true.';
COMMENT ON COLUMN "tbl_listener-profile".version IS
    'JPA optimistic-lock token; visibility commands use it to reject stale devices.';

COMMIT;
