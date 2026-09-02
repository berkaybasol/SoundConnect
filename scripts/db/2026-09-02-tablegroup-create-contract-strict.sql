-- Finalizes the strict TableGroup create contract for the first production release.
--
-- Run after the TableGroup description, meeting-time, chat-idempotency, and
-- notification-type migrations with psql -v ON_ERROR_STOP=1. The application
-- must be stopped while this migration runs. It deliberately refuses to invent
-- descriptions or idempotency keys for inconsistent pre-release data.

BEGIN;

DO $table_group_create_contract_schema$
BEGIN
    IF to_regclass(format('%I.%I', current_schema(), 'tbl_table_group')) IS NULL THEN
        RAISE EXCEPTION
            'TableGroup strict create contract requires tbl_table_group';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM information_schema.columns
         WHERE table_schema = current_schema()
           AND table_name = 'tbl_table_group'
           AND column_name = 'owner_id'
           AND data_type = 'uuid'
    ) OR NOT EXISTS (
        SELECT 1
          FROM information_schema.columns
         WHERE table_schema = current_schema()
           AND table_name = 'tbl_table_group'
           AND column_name = 'create_request_key'
           AND data_type = 'uuid'
    ) THEN
        RAISE EXCEPTION
            'TableGroup strict create contract requires UUID owner_id/create_request_key columns';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM information_schema.columns
         WHERE table_schema = current_schema()
           AND table_name = 'tbl_table_group'
           AND column_name = 'description'
           AND data_type = 'character varying'
           AND character_maximum_length = 280
    ) THEN
        RAISE EXCEPTION
            'TableGroup strict create contract requires description varchar(280)';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM information_schema.columns
         WHERE table_schema = current_schema()
           AND table_name = 'tbl_table_group'
           AND column_name = 'start_at'
           AND data_type = 'timestamp with time zone'
    ) OR NOT EXISTS (
        SELECT 1
          FROM information_schema.columns
         WHERE table_schema = current_schema()
           AND table_name = 'tbl_table_group'
           AND column_name = 'meeting_at'
           AND data_type = 'timestamp with time zone'
    ) OR NOT EXISTS (
        SELECT 1
          FROM information_schema.columns
         WHERE table_schema = current_schema()
           AND table_name = 'tbl_table_group'
           AND column_name = 'expires_at'
           AND data_type = 'timestamp with time zone'
    ) THEN
        RAISE EXCEPTION
            'TableGroup strict create contract requires timestamptz start_at/meeting_at/expires_at columns';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM information_schema.columns
         WHERE table_schema = current_schema()
           AND table_name = 'tbl_table_group'
           AND column_name = 'status'
           AND data_type IN ('character varying', 'text')
    ) THEN
        RAISE EXCEPTION
            'TableGroup strict create contract requires a text status column';
    END IF;

    IF to_regclass(format('%I.%I', current_schema(), 'tbl_table_group_message')) IS NULL THEN
        RAISE EXCEPTION
            'TableGroup strict create contract requires tbl_table_group_message';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM information_schema.columns
         WHERE table_schema = current_schema()
           AND table_name = 'tbl_table_group_message'
           AND column_name = 'message_type'
           AND data_type IN ('character varying', 'text')
           AND is_nullable = 'NO'
    ) OR NOT EXISTS (
        SELECT 1
          FROM information_schema.columns
         WHERE table_schema = current_schema()
           AND table_name = 'tbl_table_group_message'
           AND column_name = 'client_message_id'
           AND data_type = 'uuid'
           AND is_nullable = 'YES'
    ) THEN
        RAISE EXCEPTION
            'TableGroup strict create contract requires non-null text message_type and nullable UUID client_message_id';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conrelid = to_regclass(format('%I.%I', current_schema(), 'tbl_table_group'))
           AND conname = 'ck_table_group_description'
           AND contype = 'c'
           AND convalidated
    ) OR NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conrelid = to_regclass(format('%I.%I', current_schema(), 'tbl_table_group'))
           AND conname = 'ck_table_group_meeting_time'
           AND contype = 'c'
           AND convalidated
    ) THEN
        RAISE EXCEPTION
            'TableGroup strict create contract requires validated description and meeting-time predecessor constraints';
    END IF;
END
$table_group_create_contract_schema$;

-- The preflight and every DDL change share one write fence. This prevents a
-- create racing between the data audit and the final NOT NULL/UNIQUE contract.
LOCK TABLE tbl_table_group IN ACCESS EXCLUSIVE MODE;

DO $table_group_create_contract_data$
BEGIN
    -- Only immutable terminal rows from the pre-release local database may
    -- retain an unknown description. No active/unknown state is grandfathered.
    IF EXISTS (
        SELECT 1
          FROM tbl_table_group
         WHERE description IS NULL
           AND (
               status IS NULL
               OR status NOT IN ('INACTIVE', 'CANCELLED')
           )
    ) THEN
        RAISE EXCEPTION
            'TableGroup strict create contract found non-terminal rows without description'
            USING ERRCODE = '23514';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM tbl_table_group
         WHERE meeting_at IS NULL
    ) THEN
        RAISE EXCEPTION
            'TableGroup strict create contract found rows without meeting_at'
            USING ERRCODE = '23502';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM tbl_table_group
         WHERE create_request_key IS NULL
    ) THEN
        RAISE EXCEPTION
            'TableGroup strict create contract found rows without create_request_key'
            USING ERRCODE = '23502';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM tbl_table_group
         GROUP BY owner_id, create_request_key
        HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION
            'TableGroup strict create contract found duplicate owner/create_request_key pairs'
            USING ERRCODE = '23505';
    END IF;
END
$table_group_create_contract_data$;

-- NOT VALID intentionally preserves already-audited terminal rows with a NULL
-- description. PostgreSQL still enforces this check for every new or updated
-- row, so no new create can use any compatibility-era omission.
ALTER TABLE tbl_table_group
    DROP CONSTRAINT IF EXISTS ck_table_group_create_required_fields;
ALTER TABLE tbl_table_group
    ADD CONSTRAINT ck_table_group_create_required_fields
    CHECK (
        description IS NOT NULL
        AND meeting_at IS NOT NULL
        AND create_request_key IS NOT NULL
    ) NOT VALID;

ALTER TABLE tbl_table_group
    ALTER COLUMN meeting_at SET NOT NULL,
    ALTER COLUMN create_request_key SET NOT NULL;

-- Hibernate bootstraps this identity as a table UNIQUE constraint, whereas the
-- hardening migration creates a standalone partial unique index with the same
-- name. Drop the possible owner first, then converge both histories on one full
-- table constraint. No CASCADE: an unexpected dependency must abort safely.
ALTER TABLE tbl_table_group
    DROP CONSTRAINT IF EXISTS uk_table_group_owner_create_request;
DROP INDEX IF EXISTS uk_table_group_owner_create_request;
ALTER TABLE tbl_table_group
    ADD CONSTRAINT uk_table_group_owner_create_request
    UNIQUE (owner_id, create_request_key);

-- A clean first-production schema has no legacy NULL description and can reach
-- the complete column-level contract immediately. A pre-release local schema
-- with retained terminal history keeps the NOT VALID check until those rows are
-- deleted; rerunning this migration then completes the same convergence.
DO $table_group_description_convergence$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM tbl_table_group
         WHERE description IS NULL
    ) THEN
        ALTER TABLE tbl_table_group
            VALIDATE CONSTRAINT ck_table_group_create_required_fields;
        ALTER TABLE tbl_table_group
            ALTER COLUMN description SET NOT NULL;
    END IF;
END
$table_group_description_convergence$;

-- User-authored TEXT messages require a replay key in the first release. The
-- nullable column remains intentional because server-owned non-TEXT rows have no
-- client request. NOT VALID preserves any audited pre-release TEXT history,
-- while PostgreSQL enforces the rule for every new or updated row immediately.
ALTER TABLE tbl_table_group_message
    DROP CONSTRAINT IF EXISTS ck_table_group_text_client_message_id;
ALTER TABLE tbl_table_group_message
    ADD CONSTRAINT ck_table_group_text_client_message_id
    CHECK (message_type <> 'TEXT' OR client_message_id IS NOT NULL) NOT VALID;

DO $table_group_text_client_message_id_convergence$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM tbl_table_group_message
         WHERE message_type = 'TEXT'
           AND client_message_id IS NULL
    ) THEN
        ALTER TABLE tbl_table_group_message
            VALIDATE CONSTRAINT ck_table_group_text_client_message_id;
    END IF;
END
$table_group_text_client_message_id_convergence$;

COMMENT ON CONSTRAINT ck_table_group_create_required_fields ON tbl_table_group IS
    'Required on every new/updated row; NOT VALID only while audited immutable terminal rows retain a legacy NULL description.';
COMMENT ON COLUMN tbl_table_group.description IS
    'Required for every first-release create; nullable only for retained immutable pre-release terminal history.';
COMMENT ON COLUMN tbl_table_group.meeting_at IS
    'Required user-visible gathering instant; expires_at remains the technical automatic-close instant.';
COMMENT ON COLUMN tbl_table_group.create_request_key IS
    'Required deterministic create fingerprint, unique within an owner.';
COMMENT ON CONSTRAINT ck_table_group_text_client_message_id ON tbl_table_group_message IS
    'Every new/updated user TEXT message requires a replay key; server-owned non-TEXT rows may omit it.';

COMMIT;
